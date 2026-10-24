package com.smartscity.id.core;

import com.smartscity.id.api.*;
import com.smartscity.id.spi.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Owns generator lifecycle, cache and one producer. Lease state reads are local. */
public final class CachedUniqueIdGenerator implements IdGenerator {
    private final GeneratorOptions options;
    private final WorkerLease lease;
    private final MonotonicClock clock;
    private final RingBuffer ring;
    private final DefaultUniqueIdGenerator source;
    private final GeneratorMetrics metrics = new GeneratorMetrics();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<IdException> failure = new AtomicReference<>();
    private final Thread producer;
    public CachedUniqueIdGenerator(GeneratorOptions options, WorkerLease lease, TimeSource time) {
        this(options, lease, () -> new MonotonicClock(time, options.clockToleranceMillis));
    }
    /** Reuses the exact anchor that startup validated against the database. */
    public static CachedUniqueIdGenerator usingClock(GeneratorOptions options, WorkerLease lease, MonotonicClock clock) {
        return new CachedUniqueIdGenerator(options, lease, () -> Objects.requireNonNull(clock));
    }
    private CachedUniqueIdGenerator(GeneratorOptions options, WorkerLease lease, Supplier<MonotonicClock> clockFactory) {
        this.lease = Objects.requireNonNull(lease);
        try {
            this.options = Objects.requireNonNull(options);
            clock = clockFactory.get();
            ring = new RingBuffer(options.capacity);
            source = new DefaultUniqueIdGenerator(options.bits, lease.workerId());
            producer = new Thread(this::produce, "smartscity-id-fill-" + lease.rawId());
            producer.setDaemon(true);
            checkAvailable(); fill();
            if (ring.size() == 0) throw new IdException(IdException.Reason.STARTUP, "Initial cache is empty");
            producer.start();
        } catch (RuntimeException | Error e) {
            closed.set(true);
            try { lease.close(); } catch (RuntimeException | Error cleanup) { if (cleanup != e) e.addSuppressed(cleanup); }
            throw e;
        }
    }
    private void checkAvailable() {
        if (closed.get()) throw new IdException(IdException.Reason.CLOSED, "Generator closed");
        IdException terminal = failure.get();
        if (terminal != null) throw terminal;
        WorkerLease.State state = lease.state();
        if (state != WorkerLease.State.ACTIVE) {
            IdException ex = new IdException(IdException.Reason.COORDINATION, "Worker state: " + state);
            if (state == WorkerLease.State.EXPIRED || state == WorkerLease.State.CLOSED) failure.compareAndSet(null, ex);
            throw ex;
        }
        try {
            long second = clock.seconds();
            if (second < 0 || second > options.bits.getMaxTime()) {
                throw new IdException(IdException.Reason.CAPACITY, "Epoch time range exhausted");
            }
        } catch (IdException e) { failure.compareAndSet(null, e); throw e; }
    }
    @Override public long getUniqueId() {
        long start = System.nanoTime(), waitingSince = 0;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new IdException(IdException.Reason.INTERRUPTED, "ID wait interrupted");
                checkAvailable();
                long id = ring.poll();
                if (id != 0) {
                    checkAvailable(); // claimed but unhealthy IDs are burned, never requeued
                    if (waitingSince != 0 && System.nanoTime() - start >= options.waitNanos) {
                        throw new IdException(IdException.Reason.TIMEOUT, "ID wait deadline exceeded");
                    }
                    metrics.generated.increment();
                    if (ring.size() <= options.capacity * options.lowWaterPercent / 100) LockSupport.unpark(producer);
                    return id;
                }
                if (waitingSince == 0) { waitingSince = System.nanoTime(); metrics.waits.increment(); }
                long remaining = options.waitNanos - (System.nanoTime() - start);
                if (remaining <= 0) throw new IdException(IdException.Reason.TIMEOUT, "ID cache wait timed out");
                LockSupport.unpark(producer);
                LockSupport.parkNanos(this, Math.min(remaining, 100_000));
            }
        } catch (IdException e) { metrics.failed(e.getReason()); throw e; }
        finally {
            long end = System.nanoTime(); metrics.latency(end - start);
            if (waitingSince != 0) metrics.waitNanos.add(end - waitingSince);
        }
    }
    private void fill() {
        long start = System.nanoTime();
        try {
            while (!closed.get() && ring.size() < ring.capacity()) {
                if (Thread.currentThread().isInterrupted()) throw new IdException(IdException.Reason.INTERRUPTED, "Cache initialization interrupted");
                checkAvailable();
                long id = source.next(clock.seconds());
                if (id == 0 || !ring.offer(id)) break;
            }
        } finally { metrics.refillNanos.add(System.nanoTime() - start); }
    }
    private void produce() {
        while (!closed.get() && failure.get() == null) {
            try { checkAvailable(); fill(); }
            catch (IdException e) {
                if (e.getReason() != IdException.Reason.COORDINATION && e.getReason() != IdException.Reason.CLOSED) {
                    metrics.refillFailures.increment(); failure.compareAndSet(null, e);
                }
            } catch (RuntimeException | Error e) {
                metrics.refillFailures.increment();
                failure.compareAndSet(null, new IdException(IdException.Reason.INTERNAL, "Cache producer failed", e));
                if (e instanceof VirtualMachineError || e instanceof ThreadDeath) throw (Error) e;
            }
            // One thread + unpark permit merges refill signals; periodic check prevents missed wakeups.
            LockSupport.parkNanos(this, 1_000_000);
        }
    }
    @Override public IdParts parse(long id) { return options.bits.parse(id); }
    @Override public IdSnapshot snapshot() {
        IdException terminal = failure.get();
        WorkerLease.State leaseState = lease.state();
        boolean ready = !closed.get() && terminal == null && leaseState == WorkerLease.State.ACTIVE;
        String state = closed.get() ? "CLOSED" : terminal != null ? terminal.getReason().name() : leaseState.name();
        long oldest = ring.oldestId();
        long age = oldest == 0 ? 0 : Math.max(0, System.currentTimeMillis() - parse(oldest).getTimestamp().toEpochMilli());
        return IdSnapshot.builder().startedAtMillis(clock.getAnchorMillis()).ready(ready).state(state).rawWorkerId(lease.rawId()).workerId(lease.workerId())
                .generated(metrics.generated.sum()).waits(metrics.waits.sum()).waitNanos(metrics.waitNanos.sum())
                .latencyNanos(metrics.latencyNanos.sum()).clockDeviationMillis(clock.getDeviationMillis())
                .oldestAgeMillis(age).refillNanos(metrics.refillNanos.sum()).refillFailures(metrics.refillFailures.sum())
                .latencyP50Nanos(metrics.percentile(0.50)).latencyP95Nanos(metrics.percentile(0.95))
                .latencyP99Nanos(metrics.percentile(0.99)).buffered(ring.size()).failures(metrics.failures()).build();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        LockSupport.unpark(producer);
        try { producer.join(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { lease.close(); }
    }
}
