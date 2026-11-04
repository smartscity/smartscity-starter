package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.RegistryCapacity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies nonblocking sampling, bounded ownership, failure freshness and late cleanup.
 *
 * @author jack
 */
class RegistryCapacityMonitorTest {
  private RegistryCapacity initial() {
    return RegistryCapacity.observed(4, 3, 123, RegistryCapacity.Status.REGISTERED);
  }

  @Test
  @Timeout(5)
  void oneBlockedReaderDoesNotBlockSnapshotsOrSpawnMoreReaders() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    try (RegistryCapacityMonitor monitor =
        new RegistryCapacityMonitor(
            () -> {
              reads.incrementAndGet();
              entered.countDown();
              release.await();
              return RegistryCapacity.observed(4, 1, 456, RegistryCapacity.Status.OBSERVED);
            },
            initial(),
            TimeUnit.MINUTES.toNanos(1))) {
      assertEquals(3, monitor.snapshot().getRemaining());
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      for (int i = 0; i < 100; i++) {
        assertEquals(3, monitor.snapshot().getRemaining());
      }
      assertEquals(1, reads.get());
      release.countDown();
      await(() -> monitor.snapshot().getStatus() == RegistryCapacity.Status.OBSERVED);
      assertEquals(1, monitor.snapshot().getRemaining());
      assertEquals(456, monitor.snapshot().getObservedAtMillis());
    } finally {
      release.countDown();
    }
  }

  @Test
  void failureRetainsValuesAndTimestampAndDoesNotRetryOnEverySnapshot() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    try (RegistryCapacityMonitor monitor =
        new RegistryCapacityMonitor(
            () -> {
              reads.incrementAndGet();
              throw new java.sql.SQLException("Unavailable");
            },
            initial(),
            TimeUnit.MINUTES.toNanos(1))) {
      await(() -> monitor.snapshot().getStatus() == RegistryCapacity.Status.FAILED);
      assertEquals(3, monitor.snapshot().getRemaining());
      assertEquals(123, monitor.snapshot().getObservedAtMillis());
      assertEquals(1, reads.get());
    }
  }

  @Test
  @Timeout(5)
  void closeRejectsLatePublicationEvenWhenTheReaderIgnoresInterrupt() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    RegistryCapacityMonitor monitor =
        new RegistryCapacityMonitor(
            () -> {
              reads.incrementAndGet();
              entered.countDown();
              boolean done = false;
              while (!done) {
                try {
                  release.await();
                  done = true;
                } catch (InterruptedException ignored) {
                  // Model a JDBC driver that cannot be canceled; the monitor still owns the single
                  // read.
                }
              }
              finished.countDown();
              return RegistryCapacity.observed(4, 0, 456, RegistryCapacity.Status.OBSERVED);
            },
            initial(),
            1);
    try {
      monitor.snapshot();
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      monitor.close();
      release.countDown();
      assertTrue(finished.await(1, TimeUnit.SECONDS));
      assertEquals(RegistryCapacity.Status.REGISTERED, monitor.snapshot().getStatus());
      assertEquals(1, reads.get());
    } finally {
      monitor.close();
      release.countDown();
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertTrue(condition.getAsBoolean());
  }
}
