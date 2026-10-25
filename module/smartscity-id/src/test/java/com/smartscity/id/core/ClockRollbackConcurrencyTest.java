package com.smartscity.id.core;

import static com.smartscity.id.core.ClockRollbackTest.clockFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies terminal clock failures and retained ID uniqueness under concurrent consumers.
 *
 * @author jack
 */
@Timeout(20)
class ClockRollbackConcurrencyTest {
  private static final long BASE = ControlledTime.BASE;

  private static GeneratorOptions options(BitsAllocator bits) {
    return GeneratorOptions.builder()
        .bits(bits)
        .capacity(32)
        .waitTimeout(Duration.ofSeconds(1))
        .build();
  }

  static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(condition.getAsBoolean(), "Condition did not become true within 2 seconds");
  }

  @Test
  void concurrentConsumersRemainUniqueDuringOscillationAndStopAfterTerminalFailure()
      throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(4);
    try {
      for (BitsAllocator bits : ClockRollbackTest.layouts()) {
        ControlledTime time = new ControlledTime();
        CoreTest.Lease lease = new CoreTest.Lease();
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (CachedUniqueIdGenerator generator =
            new CachedUniqueIdGenerator(options(bits), lease, time)) {
          for (int step = 1; step <= 32; step++) {
            long logicalMillis = BASE + step * 1000L;
            time.set(logicalMillis + (step % 2 == 0 ? -1000 : 1000), step * 1_000_000_000L);
            List<Future<?>> batch = new ArrayList<>();
            for (int c = 0; c < 4; c++) {
              batch.add(
                  callers.submit(
                      () -> {
                        for (int i = 0; i < 16; i++) {
                          long id = generator.getUniqueId();
                          assertTrue(ids.add(id));
                          assertTrue(
                              generator.parse(id).getTimestamp().toEpochMilli() <= logicalMillis);
                        }
                      }));
            }
            for (Future<?> future : batch) {
              future.get(2, TimeUnit.SECONDS);
            }
          }
          assertEquals(2048, ids.size());
          assertEquals(2048, generator.snapshot().getGenerated());
          time.set(BASE + 32000 - 2000, 32_000_000_000L);
          await(() -> generator.snapshot().getState().equals("CLOCK"));
          time.set(BASE + 32000, 32_000_000_000L);
          List<Future<?>> rejected = new ArrayList<>();
          for (int c = 0; c < 4; c++) {
            rejected.add(
                callers.submit(
                    () -> {
                      for (int i = 0; i < 100; i++) {
                        clockFailure(generator::getUniqueId);
                      }
                    }));
          }
          for (Future<?> future : rejected) {
            future.get(2, TimeUnit.SECONDS);
          }
          assertEquals(2048, generator.snapshot().getGenerated());
          assertFalse(generator.snapshot().isReady());
          assertEquals(400L, generator.snapshot().getFailures().get(IdException.Reason.CLOCK));
        }
        assertEquals(1, lease.closes.get());
      }
    } finally {
      callers.shutdownNow();
      assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void producerFailurePublishedAfterClaimBurnsTheIdAndCannotBeCleared() throws Exception {
    Thread caller = Thread.currentThread();
    AtomicBoolean armed = new AtomicBoolean();
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    ControlledTime base = new ControlledTime();
    CachedUniqueIdGenerator[] holder = new CachedUniqueIdGenerator[1];
    try (PausedProducerTime paused = new PausedProducerTime(base)) {
      WorkerLease lease =
          new WorkerLease() {
            public long rawId() {
              return 1;
            }

            public long workerId() {
              return 1;
            }

            public State state() {
              if (armed.get() && Thread.currentThread() == caller && reads.incrementAndGet() == 2) {
                // The second admission read is after the claim. Let the producer publish CLOCK
                // before this read returns; the final gate must burn that claimed ID.
                base.set(BASE - 2000, 0);
                paused.close();
                try {
                  await(() -> holder[0].snapshot().getState().equals("CLOCK"));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                }
              }
              return State.ACTIVE;
            }

            public void close() {
              closes.incrementAndGet();
            }
          };
      try (CachedUniqueIdGenerator generator =
          new CachedUniqueIdGenerator(
              GeneratorOptions.builder().capacity(4).build(), lease, paused)) {
        holder[0] = generator;
        paused.awaitPaused();
        assertEquals(4, generator.snapshot().getBuffered());
        armed.set(true);
        clockFailure(generator::getUniqueId);
        armed.set(false);
        assertEquals(3, generator.snapshot().getBuffered());
        assertEquals(0, generator.snapshot().getGenerated());
        base.set(BASE, 0);
        for (int i = 0; i < 10; i++) {
          clockFailure(generator::getUniqueId);
        }
        assertEquals(3, generator.snapshot().getBuffered());
      }
    }
    assertEquals(1, closes.get());
  }

  @Test
  void emptyCacheWaitersObserveRollbackBeforeTheirDeadline() throws Exception {
    ControlledTime time = new ControlledTime();
    CoreTest.Lease lease = new CoreTest.Lease();
    ExecutorService callers = Executors.newFixedThreadPool(4);
    try (PausedProducerTime paused = new PausedProducerTime(time);
        CachedUniqueIdGenerator generator =
            new CachedUniqueIdGenerator(
                GeneratorOptions.builder()
                    .bits(new BitsAllocator(20, 4, 1))
                    .capacity(2)
                    .waitTimeout(Duration.ofSeconds(1))
                    .build(),
                lease,
                paused)) {
      paused.awaitPaused();
      generator.getUniqueId();
      generator.getUniqueId();
      List<Future<?>> waiting = new ArrayList<>();
      for (int c = 0; c < 4; c++) {
        waiting.add(callers.submit(() -> clockFailure(generator::getUniqueId)));
      }
      await(() -> generator.snapshot().getWaits() >= 4);
      time.set(BASE - 2000, 0);
      paused.close();
      for (Future<?> future : waiting) {
        future.get(2, TimeUnit.SECONDS);
      }
      assertEquals(2, generator.snapshot().getGenerated());
      assertEquals("CLOCK", generator.snapshot().getState());
      time.set(BASE, 0);
      clockFailure(generator::getUniqueId);
    } finally {
      callers.shutdownNow();
      assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
    }
    assertEquals(1, lease.closes.get());
  }

  @Test
  void idleFullCacheStillDetectsClockFailure() throws Exception {
    ControlledTime time = new ControlledTime();
    try (CachedUniqueIdGenerator generator =
        new CachedUniqueIdGenerator(
            options(BitsAllocator.standard()), new CoreTest.Lease(), time)) {
      time.set(BASE + 2000, 0);
      await(() -> generator.snapshot().getRefillFailures() > 0);
      assertEquals("CLOCK", generator.snapshot().getState());
      assertEquals(32, generator.snapshot().getBuffered());
      time.set(BASE, 0);
      clockFailure(generator::getUniqueId);
    }
  }

  @Test
  void unstableSamplingStopsGeneratorPermanently() throws Exception {
    AtomicBoolean unstable = new AtomicBoolean();
    AtomicLong nanos = new AtomicLong();
    TimeSource time =
        new TimeSource() {
          public long nanoTime() {
            return nanos.get();
          }

          public long wallTimeMillis() {
            if (unstable.get()) {
              nanos.addAndGet(2_000_000);
            }
            return BASE + nanos.get() / 1_000_000;
          }
        };
    try (CachedUniqueIdGenerator generator =
        new CachedUniqueIdGenerator(
            options(BitsAllocator.standard()), new CoreTest.Lease(), time)) {
      unstable.set(true);
      await(() -> generator.snapshot().getState().equals("CLOCK"));
      clockFailure(generator::getUniqueId);
      unstable.set(false);
      clockFailure(generator::getUniqueId);
      assertEquals("CLOCK", generator.snapshot().getState());
    }
  }
}
