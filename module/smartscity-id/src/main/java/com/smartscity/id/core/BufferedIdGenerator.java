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
public final class BufferedIdGenerator implements IdGenerator {
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
  private final GeneratorMetrics metrics;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<IdException> failure = new AtomicReference<>();
  private final Thread producer;
  private final ConsumerWaiters waiters = new ConsumerWaiters();

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
  public BufferedIdGenerator(GeneratorOptions options, WorkerLease lease, TimeSource time) {
    this(options, lease, () -> new MonotonicClock(time, options.clockToleranceMillis));
  }

  /**
   * Starts a buffered generator with the clock anchor already validated during external
   * registration.
   *
   * <p>The supplied clock is used by startup and the producer. Consumers read its published failure
   * state without sampling time. This method neither creates a replacement anchor nor performs
   * database calibration; the caller must validate the anchor and allocate an exclusive worker
   * before calling it.
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
  public static BufferedIdGenerator usingClock(
      GeneratorOptions options, WorkerLease lease, MonotonicClock clock) {
    return new BufferedIdGenerator(options, lease, () -> Objects.requireNonNull(clock));
  }

  private BufferedIdGenerator(
      GeneratorOptions options, WorkerLease lease, Supplier<MonotonicClock> clockFactory) {
    this.lease = Objects.requireNonNull(lease);
    try {
      this.options = Objects.requireNonNull(options);
      metrics = new GeneratorMetrics(options.latencySampleInterval);
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

  private void checkState() {
    if (closed.get()) {
      throw new IdException(IdException.Reason.CLOSED, "Generator closed");
    }
    WorkerLease.State state = lease.state();
    IdException terminal = failure.get();
    if (terminal != null) {
      throw terminal;
    }
    if (state != WorkerLease.State.ACTIVE) {
      throw unavailableWorker(state);
    }
  }

  private long checkAvailable() {
    checkState();
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
    boolean sampleLatency = metrics.sampleLatency();
    boolean generated = false;
    try {
      checkInterrupted();
      checkState();
      long id = ring.pollClaim();
      if (id <= 0) {
        id = awaitId(start, id);
      } else {
        requestRefill(); // release must notify even when post-claim admission burns this ID
        checkState(); // claimed but unhealthy IDs are burned, never requeued
      }
      generated = true;
      return id;
    } catch (IdException e) {
      metrics.failed(e.getReason());
      throw e;
    } finally {
      if (sampleLatency) {
        metrics.completed(System.nanoTime() - start, generated);
      } else {
        metrics.completedWithoutLatency(generated);
      }
    }
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new IdException(IdException.Reason.INTERRUPTED, "ID wait interrupted");
    }
  }

  /** Owns unavailable-claim retries and their original call-start deadline. */
  private long awaitId(long start, long claim) {
    long waitingSince = System.nanoTime();
    int contentionRetries = 0;
    long emptyPolls = 0;
    long contendedPolls = 0;
    long parks = 0;
    long parkedNanos = 0;
    metrics.waits.increment();
    try {
      while (true) {
        checkInterrupted();
        checkState();
        if (claim == RingBuffer.CONTENDED) {
          contendedPolls++;
        } else {
          emptyPolls++;
        }
        long remaining = options.waitNanos - (System.nanoTime() - start);
        if (remaining <= 0) {
          if (claim == RingBuffer.CONTENDED) {
            metrics.contentionTimeouts.increment();
          } else {
            metrics.emptyTimeouts.increment();
          }
          throw timeout(
              claim == RingBuffer.CONTENDED ? "contention" : "empty",
              start,
              waitingSince,
              parks,
              parkedNanos);
        }
        requestRefill();
        if (claim == RingBuffer.CONTENDED && contentionRetries++ < 8) {
          Thread.onSpinWait();
          claim = ring.pollClaim();
        } else {
          contentionRetries = 0;
          int slot = waiters.register();
          try {
            // Registration precedes the recheck: publication either leaves a permit or is seen
            // here.
            checkInterrupted();
            checkState();
            claim = ring.pollClaim();
            if (claim <= 0) {
              remaining = options.waitNanos - (System.nanoTime() - start);
              if (remaining > 0) {
                parks++;
                long pause = claim == RingBuffer.CONTENDED ? 1_000 : 100_000;
                long parkStart = System.nanoTime();
                LockSupport.parkNanos(this, Math.min(remaining, pause));
                parkedNanos += System.nanoTime() - parkStart;
              }
              checkInterrupted();
              checkState();
              claim = ring.pollClaim();
            }
          } finally {
            waiters.cancel(slot);
          }
        }
        if (claim > 0) {
          requestRefill();
          checkState();
          if (System.nanoTime() - start >= options.waitNanos) {
            metrics.deadlineTimeouts.increment();
            throw timeout("after_claim", start, waitingSince, parks, parkedNanos);
          }
          return claim;
        }
      }
    } finally {
      // Publish once per completed wait, rather than adding shared writes to each CAS retry.
      if (emptyPolls != 0) {
        metrics.emptyPolls.add(emptyPolls);
      }
      if (contendedPolls != 0) {
        metrics.contendedPolls.add(contendedPolls);
      }
      if (parks != 0) {
        metrics.consumerParks.add(parks);
      }
      metrics.waitNanos.add(System.nanoTime() - waitingSince);
    }
  }

  /** Captures failure-only context; diagnostic text is not a machine-readable API. */
  private IdException timeout(
      String stage, long start, long waitingSince, long parks, long parkedNanos) {
    return new IdException(
        IdException.Reason.TIMEOUT,
        "ID wait deadline exceeded: stage="
            + stage
            + ", elapsedNanos="
            + (System.nanoTime() - start)
            + ", beforeWaitNanos="
            + (waitingSince - start)
            + ", parks="
            + parks
            + ", parkedNanos="
            + parkedNanos
            + ", buffered="
            + ring.size());
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
      if (Thread.currentThread().isInterrupted()) {
        throw new IdException(IdException.Reason.INTERRUPTED, "Cache initialization interrupted");
      }
      if (closed.get()) {
        return 0;
      }
      // Bound work and logical advancement to the available capacity observed at chunk entry.
      // Slot reclamation still decides admission; head advancement alone does not free a slot.
      int limit = Math.min(REFILL_CHUNK, ring.capacity() - ring.size());
      int produced = source.fillBuffered(ring, second, limit);
      if (produced > 0) {
        waiters.signalAll();
      }
      return produced;
    } finally {
      metrics.refillNanos.add(System.nanoTime() - start);
    }
  }

  private void produce() {
    while (!closed.get() && failure.get() == null) {
      int produced = 0;
      boolean refillAllowed = false;
      try {
        produced = fill();
        refillAllowed = true;
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
      if (failure.get() != null || closed.get()) {
        waiters.signalAll();
        break;
      }
      producerParked.set(true);
      // Arm before rechecking: a consumer racing the park either changes occupancy or leaves a
      // permit.
      if (refillAllowed && ring.size() <= lowWatermark && ring.hasReclaimedSlot()) {
        producerParked.set(false);
        continue;
      }
      LockSupport.parkNanos(this, 1_000_000);
      producerParked.set(false);
    }
  }

  IdLayout layout() {
    return options.bits.getLayout();
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
    long logicalSecond = source.publishedSecond();
    long logicalMillis =
        options.bits.getLayout().getEpoch().plusSeconds(logicalSecond).toEpochMilli();
    long nowMillis = System.currentTimeMillis();
    long lead = logicalMillis <= nowMillis ? 0 : logicalMillis - nowMillis;
    if (lead < 0) {
      lead = Long.MAX_VALUE;
    }
    long oldest = ring.oldestId();
    long age =
        oldest == 0
            ? 0
            : Math.max(0, System.currentTimeMillis() - parse(oldest).getTimestamp().toEpochMilli());
    return IdSnapshot.builder()
        .workerSlots(options.bits.getWorkerSlots())
        .registryCapacity(lease.capacity())
        .logicalTimeMillis(logicalMillis)
        .futureTimeMillis(lead)
        .remainingTimeSeconds(options.bits.getMaxTime() - logicalSecond)
        .emptyPolls(metrics.emptyPolls.sum())
        .contendedPolls(metrics.contendedPolls.sum())
        .consumerParks(metrics.consumerParks.sum())
        .emptyTimeouts(metrics.emptyTimeouts.sum())
        .contentionTimeouts(metrics.contentionTimeouts.sum())
        .deadlineTimeouts(metrics.deadlineTimeouts.sum())
        .startedAtMillis(clock.getAnchorMillis())
        .ready(ready)
        .state(state)
        .rawWorkerId(lease.rawId())
        .workerId(lease.workerId())
        .generated(metrics.generated())
        .waits(metrics.waits.sum())
        .waitNanos(metrics.waitNanos.sum())
        .latencyNanos(metrics.latencyNanos.sum())
        .latencySamples(metrics.latencySamples())
        .latencySampleInterval(options.latencySampleInterval)
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
    waiters.signalAll();
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
