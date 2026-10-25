package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies clock tolerances, lease transitions and bounded local shutdown.
 *
 * @author jack
 */
class ClockAndLifecycleTest {
  static final class PausingClock implements TimeSource {
    long nanos;
    long offset;

    boolean pauseBeforeWall;
    boolean pauseAfterWall;

    public long nanoTime() {
      return nanos;
    }

    public long wallTimeMillis() {
      if (pauseBeforeWall) {
        nanos += 2_000_000_000L;
        pauseBeforeWall = false;
      }
      long wall = BitsAllocator.EPOCH.toEpochMilli() + 100_000 + nanos / 1_000_000 + offset;
      if (pauseAfterWall) {
        nanos += 2_000_000_000L;
        pauseAfterWall = false;
      }
      return wall;
    }
  }

  @Test
  void schedulingPauseBetweenClockReadsIsNotClockRollback() {
    PausingClock source = new PausingClock();
    MonotonicClock clock = new MonotonicClock(source, 1000);
    source.pauseBeforeWall = true;
    assertEquals(102, clock.seconds());
    source.pauseAfterWall = true;
    assertEquals(104, clock.seconds());
    source.offset = -1001;
    assertEquals(
        IdException.Reason.CLOCK, assertThrows(IdException.class, clock::seconds).getReason());
  }

  @Test
  void initialAnchorDiscardsAnInterruptedSample() {
    PausingClock source = new PausingClock();
    source.pauseBeforeWall = true;
    MonotonicClock clock = new MonotonicClock(source, 1000);
    assertEquals(102, clock.seconds());
    source.nanos += 1_000_000_000L;
    assertEquals(103, clock.seconds());
  }

  @Test
  void rollbackAfterStartupValidationCannotBeHiddenByANewAnchor() {
    PausingClock source = new PausingClock();
    MonotonicClock validated = new MonotonicClock(source, 1000);
    source.offset = -2000;
    CoreTest.Lease lease = new CoreTest.Lease();
    assertEquals(
        IdException.Reason.CLOCK,
        assertThrows(
                IdException.class,
                () ->
                    CachedUniqueIdGenerator.usingClock(
                        GeneratorOptions.builder().capacity(8).build(), lease, validated))
            .getReason());
    assertEquals(1, lease.closes.get());
  }

  @Test
  @Timeout(5)
  void concurrentCloseRejectsAnEmptyCacheWaiterAndReleasesLeaseOnce() throws Exception {
    CoreTest.Lease lease = new CoreTest.Lease();
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try (PausedProducerTime time = new PausedProducerTime(new CoreTest.Clock());
        CachedUniqueIdGenerator g =
            new CachedUniqueIdGenerator(
                GeneratorOptions.builder()
                    .bits(new BitsAllocator(20, 4, 1))
                    .capacity(2)
                    .waitTimeout(java.time.Duration.ofSeconds(1))
                    .build(),
                lease,
                time)) {
      time.awaitPaused();
      g.getUniqueId();
      g.getUniqueId();
      Future<IdException> waiting =
          pool.submit(() -> assertThrows(IdException.class, g::getUniqueId));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (g.snapshot().getWaits() == 0 && System.nanoTime() < deadline) {
        Thread.yield();
      }
      assertTrue(g.snapshot().getWaits() > 0);
      Future<?>[] closes = new Future<?>[4];
      for (int i = 0; i < closes.length; i++) {
        closes[i] = pool.submit(g::close);
      }
      for (Future<?> close : closes) {
        close.get(2, TimeUnit.SECONDS);
      }
      assertEquals(IdException.Reason.CLOSED, waiting.get(2, TimeUnit.SECONDS).getReason());
      assertEquals(1, lease.closes.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void initializationFailureReleasesLeaseAndPreservesOriginalError() {
    AtomicInteger closed = new AtomicInteger();
    WorkerLease lease =
        new WorkerLease() {
          public long rawId() {
            return 1;
          }

          public long workerId() {
            throw new IllegalArgumentException("bad worker");
          }

          public State state() {
            return State.ACTIVE;
          }

          public void close() {
            closed.incrementAndGet();
            throw new IllegalStateException("cleanup failure");
          }
        };
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new CachedUniqueIdGenerator(
                    GeneratorOptions.builder().capacity(8).build(), lease, TimeSource.SYSTEM));
    assertEquals(1, closed.get());
    assertEquals("cleanup failure", failure.getSuppressed()[0].getMessage());
  }

  @Test
  @Timeout(5)
  void producerErrorPermanentlyClosesIssuanceGate() throws Exception {
    AtomicBoolean failProducer = new AtomicBoolean();
    TimeSource time =
        new TimeSource() {
          public long nanoTime() {
            return System.nanoTime();
          }

          public long wallTimeMillis() {
            if (failProducer.get()
                && Thread.currentThread().getName().startsWith("smartscity-id-fill-")) {
              throw new LinkageError("injected producer failure");
            }
            return System.currentTimeMillis();
          }
        };
    try (CachedUniqueIdGenerator g =
        new CachedUniqueIdGenerator(
            GeneratorOptions.builder().capacity(8).build(), new CoreTest.Lease(), time)) {
      failProducer.set(true);
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (g.snapshot().isReady() && System.nanoTime() < end) {
        Thread.sleep(1);
      }
      assertFalse(g.snapshot().isReady());
      assertEquals(
          IdException.Reason.INTERNAL, assertThrows(IdException.class, g::getUniqueId).getReason());
    }
  }
}
