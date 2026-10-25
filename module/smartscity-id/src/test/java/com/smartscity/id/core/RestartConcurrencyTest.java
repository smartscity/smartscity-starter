package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.TimeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Exercises repeated local initialization and close races under explicit restart-test opt-in.
 *
 * @author jack
 */
@EnabledIfSystemProperty(named = "id.test.restart", matches = "true")
class RestartConcurrencyTest {
  @Test
  void forwardAndBackwardClockToleranceBoundariesAcrossRepeatedSecondChanges() {
    CoreTest.Clock source = new CoreTest.Clock();
    MonotonicClock clock = new MonotonicClock(source, 1000);
    for (int second = 101; second <= 200; second++) {
      source.nanos.addAndGet(1_000_000_000L);
      for (long offset : new long[] {-1000, -999, 0, 999, 1000}) {
        source.offset = offset;
        assertEquals(second, clock.seconds());
      }
      for (long offset : new long[] {-1001, 1001}) {
        source.offset = offset;
        assertEquals(
            IdException.Reason.CLOCK, assertThrows(IdException.class, clock::seconds).getReason());
      }
    }
    // The clock is a validator; terminal failure retention belongs to CachedUniqueIdGenerator.
  }

  @Test
  @Timeout(20)
  void tinyRingsRetainExactlyOnceDeliveryWithYieldingConsumers() throws Exception {
    for (int capacity : new int[] {2, 4}) {
      RingBuffer ring = new RingBuffer(capacity);
      int count = 10000;
      AtomicIntegerArray seen = new AtomicIntegerArray(count + 1);
      AtomicInteger consumed = new AtomicInteger();
      ExecutorService executor = Executors.newFixedThreadPool(9);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
      try {
        List<Future<?>> jobs = new ArrayList<>();
        jobs.add(
            executor.submit(
                () -> {
                  for (int id = 1; id <= count; id++) {
                    while (!ring.offer(id)) {
                      withinDeadline(deadline);
                      Thread.yield();
                    }
                  }
                }));
        for (int thread = 0; thread < 8; thread++) {
          jobs.add(
              executor.submit(
                  () -> {
                    while (consumed.get() < count) {
                      withinDeadline(deadline);
                      long id = ring.poll();
                      if (id == 0) {
                        Thread.yield();
                        continue;
                      }
                      if ((id & 31) == 0) {
                        Thread.yield();
                      }
                      assertTrue(id > 0 && id <= count);
                      assertEquals(1, seen.incrementAndGet((int) id));
                      consumed.incrementAndGet();
                    }
                  }));
        }
        for (Future<?> job : jobs) {
          job.get(8, TimeUnit.SECONDS);
        }
        for (int id = 1; id <= count; id++) {
          assertEquals(1, seen.get(id));
        }
        assertEquals(0, ring.poll());
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
      }
    }
  }

  private static void withinDeadline(long deadline) {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException();
    }
    assertTrue(System.nanoTime() < deadline, "Ring stopped making progress");
  }

  @Test
  @Timeout(30)
  void fiftyCloseRacesStopAllConsumersAndReleaseEachLeaseOnce() throws Exception {
    for (int cycle = 0; cycle < 50; cycle++) {
      CoreTest.Lease lease = new CoreTest.Lease();
      Set<Long> ids = ConcurrentHashMap.newKeySet();
      CountDownLatch issuing = new CountDownLatch(100);
      ExecutorService callers = Executors.newFixedThreadPool(6);
      try (CachedUniqueIdGenerator generator =
          new CachedUniqueIdGenerator(
              GeneratorOptions.builder().capacity(2).build(), lease, TimeSource.SYSTEM)) {
        List<Future<?>> consumers = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        for (int i = 0; i < 4; i++) {
          consumers.add(
              callers.submit(
                  () -> {
                    while (true) {
                      withinDeadline(deadline);
                      try {
                        assertTrue(ids.add(generator.getUniqueId()));
                        issuing.countDown();
                      } catch (IdException e) {
                        if (e.getReason() == IdException.Reason.CLOSED) {
                          return;
                        }
                        if (e.getReason() != IdException.Reason.TIMEOUT) {
                          throw e;
                        }
                      }
                    }
                  }));
        }
        assertTrue(issuing.await(2, TimeUnit.SECONDS));
        Future<?> closeA = callers.submit(generator::close);
        Future<?> closeB = callers.submit(generator::close);

        closeA.get(2, TimeUnit.SECONDS);
        closeB.get(2, TimeUnit.SECONDS);
        for (Future<?> consumer : consumers) {
          consumer.get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, lease.closes.get());
        assertEquals(
            IdException.Reason.CLOSED,
            assertThrows(IdException.class, generator::getUniqueId).getReason());
      } finally {
        callers.shutdownNow();
        assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
      }
    }
    System.out.println(
        "CLOSE_RACE cycles=50 consumers=4 capacity=2 duplicateClaims=0 leaseClosesPerCycle=1");
  }
}
