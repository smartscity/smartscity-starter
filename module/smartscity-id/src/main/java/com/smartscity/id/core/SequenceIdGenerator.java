package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdParts;
import com.smartscity.id.api.IdSnapshot;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Synchronous allocation using the same logical-second cursor as the buffered generator. One
 * bounded lock protects the cursor; no ring or producer thread is created. Clock validation occurs
 * on each call. Sequence exhaustion borrows logical time just as buffered allocation does.
 *
 * @author jack
 */
public final class SequenceIdGenerator implements IdGenerator {
  private final GeneratorOptions options;
  private final WorkerLease lease;
  private final MonotonicClock clock;
  private final DefaultUniqueIdGenerator source;
  private final GeneratorMetrics metrics;
  private final ReentrantLock lock = new ReentrantLock();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<IdException> failure = new AtomicReference<>();
  private volatile long logicalSecond;

  /**
   * Transfers an exclusively allocated lease; closes it if initialization fails.
   *
   * @param options immutable options, non-null; capacity is unused by this implementation
   * @param lease exclusively allocated worker, non-null and never reused after closure
   * @param time time source, non-null
   * @throws RuntimeException if layout, clock, ownership or arguments are invalid
   */
  public SequenceIdGenerator(GeneratorOptions options, WorkerLease lease, TimeSource time) {
    this.lease = Objects.requireNonNull(lease);
    try {
      this.options = Objects.requireNonNull(options);
      metrics = new GeneratorMetrics(options.latencySampleInterval);
      clock = new MonotonicClock(time, options.clockToleranceMillis);
      source = new DefaultUniqueIdGenerator(options.bits, lease.workerId());
      checkState();
      logicalSecond = second();
    } catch (RuntimeException | Error ex) {
      try {
        lease.close();
      } catch (RuntimeException | Error cleanup) {
        ex.addSuppressed(cleanup);
      }
      throw ex;
    }
  }

  private void checkState() {
    if (closed.get()) {
      throw new IdException(IdException.Reason.CLOSED, "Sequence generator closed");
    }
    IdException terminal = failure.get();
    if (terminal != null) {
      throw terminal;
    }
    WorkerLease.State state = lease.state();
    if (state != WorkerLease.State.ACTIVE) {
      IdException ex = new IdException(IdException.Reason.COORDINATION, "Worker state: " + state);
      if (state == WorkerLease.State.EXPIRED || state == WorkerLease.State.CLOSED) {
        failure.compareAndSet(null, ex);
      }
      throw ex;
    }
  }

  private long second() {
    try {
      long millis = clock.millis();
      IdLayout layout = options.bits.getLayout();
      if (millis < layout.getEpoch().toEpochMilli()) {
        throw new IdException(IdException.Reason.CAPACITY, "Time before epoch");
      }
      long second = Math.floorDiv(millis, 1000) - layout.getEpoch().getEpochSecond();
      if (second > options.bits.getMaxTime()) {
        throw new IdException(IdException.Reason.CAPACITY, "Timestamp range exhausted");
      }
      return second;
    } catch (IdException ex) {
      failure.compareAndSet(null, ex);
      throw ex;
    }
  }

  @Override
  public long getUniqueId() {
    long start = System.nanoTime();
    boolean sample = metrics.sampleLatency(), generated = false, acquired = false;
    try {
      if (Thread.currentThread().isInterrupted()) {
        throw new IdException(IdException.Reason.INTERRUPTED, "Interrupted before allocation");
      }
      checkState();
      if (!(acquired = lock.tryLock())) {
        metrics.waits.increment();
        long remaining = options.waitNanos - (System.nanoTime() - start);
        if (remaining <= 0 || !(acquired = lock.tryLock(remaining, TimeUnit.NANOSECONDS))) {
          throw new IdException(IdException.Reason.TIMEOUT, "Sequence lock deadline exceeded");
        }
        metrics.waitNanos.add(System.nanoTime() - start);
        if (System.nanoTime() - start >= options.waitNanos) {
          throw new IdException(IdException.Reason.TIMEOUT, "Sequence lock deadline exceeded");
        }
      }
      checkState();
      long now = second();
      long id = source.nextBuffered(now);
      logicalSecond = id >>> (options.bits.getWorkerBits() + options.bits.getSequenceBits());
      checkState();
      generated = true;
      return id;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      metrics.failed(IdException.Reason.INTERRUPTED);
      throw new IdException(
          IdException.Reason.INTERRUPTED, "Interrupted waiting for sequence lock", ex);
    } catch (IdException ex) {
      metrics.failed(ex.getReason());
      throw ex;
    } finally {
      if (acquired) {
        lock.unlock();
      }
      if (sample) {
        metrics.completed(System.nanoTime() - start, generated);
      } else {
        metrics.completedWithoutLatency(generated);
      }
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
    long logical = options.bits.getLayout().getEpoch().plusSeconds(logicalSecond).toEpochMilli();
    IdException terminal = failure.get();
    WorkerLease.State state = lease.state();
    return IdSnapshot.builder()
        .ready(!closed.get() && terminal == null && state == WorkerLease.State.ACTIVE)
        .state(
            closed.get() ? "CLOSED" : terminal != null ? terminal.getReason().name() : state.name())
        .workerId(lease.workerId())
        .rawWorkerId(lease.rawId())
        .workerSlots(options.bits.getWorkerSlots())
        .registryCapacity(lease.capacity())
        .logicalTimeMillis(logical)
        .futureTimeMillis(Math.max(0, logical - System.currentTimeMillis()))
        .remainingTimeSeconds(options.bits.getMaxTime() - logicalSecond)
        .startedAtMillis(clock.getAnchorMillis())
        .clockDeviationMillis(clock.getDeviationMillis())
        .generated(metrics.generated())
        .failures(metrics.failures())
        .waits(metrics.waits.sum())
        .waitNanos(metrics.waitNanos.sum())
        .latencySamples(metrics.latencySamples())
        .latencySampleInterval(options.latencySampleInterval)
        .latencyNanos(metrics.latencyNanos.sum())
        .latencyP50Nanos(metrics.percentile(.5))
        .latencyP95Nanos(metrics.percentile(.95))
        .latencyP99Nanos(metrics.percentile(.99))
        .build();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      lease.close();
    }
  }
}
