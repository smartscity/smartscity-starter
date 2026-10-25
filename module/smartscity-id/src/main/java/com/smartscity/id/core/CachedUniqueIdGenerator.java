package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdParts;
import com.smartscity.id.api.IdSnapshot;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Owns generator lifecycle, cache and one producer. Lease state reads are local.
 *
 * @author jack
 */
public final class CachedUniqueIdGenerator implements IdGenerator {
  private static final int REFILL_CHUNK = 256;
  private final GeneratorOptions options;
  private final long firstMillis;
  private final long lastMillis;
  private final int lowWatermark;
  private final AtomicBoolean producerParked = new AtomicBoolean();
  private final WorkerLease lease;
  private final MonotonicClock clock;
  private final RingBuffer ring;
  private final DefaultUniqueIdGenerator source;
  private final GeneratorMetrics metrics = new GeneratorMetrics();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<IdException> failure = new AtomicReference<>();
  private final Thread producer;

  /**
   * Starts a buffered generator with a new monotonic clock anchor and an allocated worker.
   *
   * <p>The caller must already own an exclusive worker. This constructor does not allocate a worker
   * or calibrate time against a database. Use {@link #usingClock} when startup has already
   * validated an anchor against an external clock.
   *
   * <p>A non-null lease transfers to this generator before the remaining arguments are validated.
   * Initialization failure closes the lease; cleanup failures are suppressed on the original
   * exception. After successful construction, concurrent callers may request IDs. The caller must
   * eventually close the generator and must not share its lease with another generator.
   *
   * @param options immutable format, buffering and clock settings; must not be null
   * @param lease exclusively allocated worker with bounded, thread-safe local state reads; must not
   *     be null
   * @param time thread-safe time source used for anchoring and subsequent validation; must not be
   *     null
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if the lease's worker number does not fit the format
   * @throws IdException if initial clock, ownership, timestamp range or cache validation fails
   */
  public CachedUniqueIdGenerator(GeneratorOptions options, WorkerLease lease, TimeSource time) {
    this(options, lease, () -> new MonotonicClock(time, options.clockToleranceMillis));
  }

  /**
   * Starts a buffered generator with the clock anchor already validated during external
   * registration.
   *
   * <p>The supplied clock is used directly by the producer and consumers. This method neither
   * creates a replacement anchor nor performs database calibration; the caller must validate the
   * anchor and allocate an exclusive worker before calling it.
   *
   * <p>A non-null lease transfers to this generator before options and clock validation.
   * Initialization failure closes the lease and attaches cleanup failures to the original
   * exception. After this method returns, the generator supports concurrent ID requests. The caller
   * must close it when no longer needed; repeated closes are harmless. Never share the lease with
   * another generator.
   *
   * @param options immutable format and local buffering settings; must not be null
   * @param lease exclusively allocated worker with bounded, thread-safe local state reads; must not
   *     be null
   * @param clock prevalidated, thread-safe clock whose tolerance matches the intended
   *     configuration; must not be null
   * @return a generator with its initial cache populated and its producer started
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if the lease's worker number does not fit the format
   * @throws IdException if initial ownership, time or cache validation fails
   */
  public static CachedUniqueIdGenerator usingClock(
      GeneratorOptions options, WorkerLease lease, MonotonicClock clock) {
    return new CachedUniqueIdGenerator(options, lease, () -> Objects.requireNonNull(clock));
  }

  private CachedUniqueIdGenerator(
      GeneratorOptions options, WorkerLease lease, Supplier<MonotonicClock> clockFactory) {
    this.lease = Objects.requireNonNull(lease);
    try {
      this.options = Objects.requireNonNull(options);
      IdLayout layout = options.bits.getLayout();
      firstMillis = layout.getEpoch().toEpochMilli();
      long lastSecondMillis =
          layout.getEpoch().plusSeconds(options.bits.getMaxTime()).toEpochMilli();
      // Include the entire last second, including layouts ending at the millisecond long limit.
      lastMillis =
          lastSecondMillis > Long.MAX_VALUE - 999 ? Long.MAX_VALUE : lastSecondMillis + 999;
      lowWatermark = options.capacity * options.lowWaterPercent / 100;
      clock = clockFactory.get();
      ring = new RingBuffer(options.capacity);
      source = new DefaultUniqueIdGenerator(options.bits, lease.workerId());
      producer = new Thread(this::produce, "smartscity-id-fill-" + lease.rawId());
      producer.setDaemon(true);
      checkAvailable();
      while (ring.size() < ring.capacity() && fill() != 0) {
        // Initialization owns the producer cursor until the background thread starts.
      }
      if (ring.size() == 0) {
        throw new IdException(IdException.Reason.STARTUP, "Initial cache is empty");
      }
      producer.start();
    } catch (RuntimeException | Error e) {
      closed.set(true);
      try {
        lease.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != e) {
          e.addSuppressed(cleanup);
        }
      }
      throw e;
    }
  }

  private long checkAvailable() {
    if (closed.get()) {
      throw new IdException(IdException.Reason.CLOSED, "Generator closed");
    }
    IdException terminal = failure.get();
    if (terminal != null) {
      throw terminal;
    }
    WorkerLease.State state = lease.state();
    if (state != WorkerLease.State.ACTIVE) {
      throw unavailableWorker(state);
    }
    try {
      long millis = clock.millis();
      if (millis < firstMillis || millis > lastMillis) {
        throw new IdException(IdException.Reason.CAPACITY, "Epoch time range exhausted");
      }
      return millis;
    } catch (IdException e) {
      failure.compareAndSet(null, e);
      throw e;
    }
  }

  private IdException unavailableWorker(WorkerLease.State state) {
    IdException ex = new IdException(IdException.Reason.COORDINATION, "Worker state: " + state);
    if (state == WorkerLease.State.EXPIRED || state == WorkerLease.State.CLOSED) {
      failure.compareAndSet(null, ex);
    }
    return ex;
  }

  @Override
  public long getUniqueId() {
    long start = System.nanoTime();
    long waitingSince = 0;
    boolean generated = false;

    try {
      while (true) {
        if (Thread.currentThread().isInterrupted()) {
          throw new IdException(IdException.Reason.INTERRUPTED, "ID wait interrupted");
        }
        checkAvailable();
        long id = ring.poll();
        if (id != 0) {
          checkAvailable(); // claimed but unhealthy IDs are burned, never requeued
          if (waitingSince != 0 && System.nanoTime() - start >= options.waitNanos) {
            throw new IdException(IdException.Reason.TIMEOUT, "ID wait deadline exceeded");
          }
          generated = true;
          requestRefill();
          return id;
        }
        if (waitingSince == 0) {
          waitingSince = System.nanoTime();
          metrics.waits.increment();
        }
        long remaining = options.waitNanos - (System.nanoTime() - start);
        if (remaining <= 0) {
          throw new IdException(IdException.Reason.TIMEOUT, "ID cache wait timed out");
        }
        requestRefill();
        LockSupport.parkNanos(this, Math.min(remaining, 100_000));
      }
    } catch (IdException e) {
      metrics.failed(e.getReason());
      throw e;
    } finally {
      long end = System.nanoTime();
      metrics.completed(end - start, generated);
      if (waitingSince != 0) {
        metrics.waitNanos.add(end - waitingSince);
      }
    }
  }

  private void requestRefill() {
    if (producerParked.get()
        && ring.size() <= lowWatermark
        && producerParked.compareAndSet(true, false)) {
      LockSupport.unpark(producer);
    }
  }

  private int fill() {
    long start = System.nanoTime();
    try {
      // Only the producer needs relative seconds. Subtract after division to avoid millisecond
      // overflow for valid layouts whose epoch and current time straddle the signed long range.
      long second =
          Math.floorDiv(checkAvailable(), 1000)
              - options.bits.getLayout().getEpoch().getEpochSecond();
      int produced = 0;
      while (produced < REFILL_CHUNK && !closed.get() && ring.size() < ring.capacity()) {
        if (Thread.currentThread().isInterrupted()) {
          throw new IdException(IdException.Reason.INTERRUPTED, "Cache initialization interrupted");
        }
        // Consumers validate before and after claiming. Producer validation is amortized per chunk.
        long id = source.nextBuffered(second);
        if (!ring.offer(id)) {
          break; // A stalled claimant can still own the next slot; the unused ID is burned.
        }
        produced++;
      }
      return produced;
    } finally {
      metrics.refillNanos.add(System.nanoTime() - start);
    }
  }

  private void produce() {
    while (!closed.get() && failure.get() == null) {
      int produced = 0;
      try {
        produced = fill();
        if (produced == REFILL_CHUNK) {
          continue;
        }
      } catch (IdException e) {
        if (e.getReason() != IdException.Reason.COORDINATION
            && e.getReason() != IdException.Reason.CLOSED) {
          metrics.refillFailures.increment();
          failure.compareAndSet(null, e);
        }
      } catch (RuntimeException | Error e) {
        metrics.refillFailures.increment();
        failure.compareAndSet(
            null, new IdException(IdException.Reason.INTERNAL, "Cache producer failed", e));
        if (e instanceof VirtualMachineError || e instanceof ThreadDeath) {
          throw (Error) e;
        }
      }
      producerParked.set(true);
      // Arm before rechecking: a consumer racing the park either changes occupancy or leaves a
      // permit.
      if (produced > 0 && ring.size() <= lowWatermark) {
        producerParked.set(false);
        continue;
      }
      LockSupport.parkNanos(this, 1_000_000);
      producerParked.set(false);
    }
  }

  @Override
  public IdParts parse(long id) {
    return options.bits.parse(id);
  }

  @Override
  public IdSnapshot snapshot() {
    IdException terminal = failure.get();
    WorkerLease.State leaseState = lease.state();
    boolean ready = !closed.get() && terminal == null && leaseState == WorkerLease.State.ACTIVE;
    String state =
        closed.get()
            ? "CLOSED"
            : terminal != null ? terminal.getReason().name() : leaseState.name();
    long oldest = ring.oldestId();
    long age =
        oldest == 0
            ? 0
            : Math.max(0, System.currentTimeMillis() - parse(oldest).getTimestamp().toEpochMilli());
    return IdSnapshot.builder()
        .startedAtMillis(clock.getAnchorMillis())
        .ready(ready)
        .state(state)
        .rawWorkerId(lease.rawId())
        .workerId(lease.workerId())
        .generated(metrics.generated())
        .waits(metrics.waits.sum())
        .waitNanos(metrics.waitNanos.sum())
        .latencyNanos(metrics.latencyNanos.sum())
        .clockDeviationMillis(clock.getDeviationMillis())
        .oldestAgeMillis(age)
        .refillNanos(metrics.refillNanos.sum())
        .refillFailures(metrics.refillFailures.sum())
        .latencyP50Nanos(metrics.percentile(0.50))
        .latencyP95Nanos(metrics.percentile(0.95))
        .latencyP99Nanos(metrics.percentile(0.99))
        .buffered(ring.size())
        .failures(metrics.failures())
        .build();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    LockSupport.unpark(producer);
    try {
      producer.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      lease.close();
    }
  }
}
