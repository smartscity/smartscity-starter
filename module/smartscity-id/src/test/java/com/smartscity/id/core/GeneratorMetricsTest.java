package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies complete outcome and duration accounting, including concurrent updates and bucket edges.
 *
 * @author jack
 */
class GeneratorMetricsTest {
  @Test
  void everyOutcomeRetainsItsDurationAndBucketUpperBound() {
    for (boolean success : new boolean[] {false, true}) {
      for (long nanos : new long[] {0, 1, 2, 3, 4, 1024, 1025, (1L << 62) + 1, Long.MAX_VALUE}) {
        GeneratorMetrics metrics = new GeneratorMetrics();
        assertEquals(0, metrics.generated());
        assertEquals(0, metrics.percentile(0.99));
        metrics.completed(nanos, success);
        long expected = 1;
        while (expected < nanos && expected <= (1L << 61)) {
          expected *= 2;
        }
        if (expected < nanos) {
          expected = Long.MAX_VALUE;
        }
        assertEquals(success ? 1 : 0, metrics.generated());
        assertEquals(nanos, metrics.latencyNanos.sum());
        assertEquals(expected, metrics.percentile(0.50));
        assertEquals(expected, metrics.percentile(0.99));
      }
    }
  }

  @Test
  @Timeout(5)
  void concurrentMixedOutcomesRemainExactAfterWritersComplete() throws Exception {
    GeneratorMetrics metrics = new GeneratorMetrics();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int thread = 0; thread < 8; thread++) {
        jobs.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 256; i++) {
                    if (i % 2 == 0) {
                      metrics.completed(1, true);
                    } else {
                      metrics.failed(IdException.Reason.TIMEOUT);
                      metrics.completed(1024, false);
                    }
                  }
                }));
      }
      for (Future<?> job : jobs) {
        job.get(3, TimeUnit.SECONDS);
      }
      assertEquals(1024, metrics.generated());
      assertEquals(1024, metrics.failures().get(IdException.Reason.TIMEOUT));
      assertEquals(1024L * 1025, metrics.latencyNanos.sum());
      assertEquals(1, metrics.percentile(0.50));
      assertEquals(1024, metrics.percentile(0.95));
      assertEquals(1024, metrics.percentile(0.99));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void unselectedOutcomesRetainCountsWithoutInventingLatency() {
    GeneratorMetrics metrics = new GeneratorMetrics(1024);
    for (int i = 0; i < 7; i++) {
      metrics.completedWithoutLatency(true);
    }
    for (int i = 0; i < 3; i++) {
      metrics.failed(IdException.Reason.TIMEOUT);
      metrics.completedWithoutLatency(false);
    }
    assertEquals(7, metrics.generated());
    assertEquals(3, metrics.failures().get(IdException.Reason.TIMEOUT));
    assertEquals(0, metrics.latencySamples());
    assertEquals(0, metrics.latencyNanos.sum());
    assertEquals(0, metrics.percentile(0.99));
  }

  @Test
  void samplingOptionsAreImmutableAndFullCollectionIsExact() {
    GeneratorOptions.Builder builder = GeneratorOptions.builder();
    GeneratorOptions defaults = builder.build();
    assertEquals(1024, defaults.latencySampleInterval);
    assertEquals(1, builder.latencySampleInterval(1).build().latencySampleInterval);
    assertEquals(1024, defaults.latencySampleInterval);
    assertEquals(1 << 20, builder.latencySampleInterval(1 << 20).build().latencySampleInterval);
    for (int invalid : new int[] {-1, 0, 3, (1 << 20) + 1, 1 << 21, Integer.MAX_VALUE}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> GeneratorOptions.builder().latencySampleInterval(invalid).build());
    }
    GeneratorMetrics full = new GeneratorMetrics(1);
    for (int i = 0; i < 32; i++) {
      assertTrue(full.sampleLatency());
      full.completed(32, true);
    }
    assertEquals(32, full.generated());
    assertEquals(32, full.latencySamples());
    assertEquals(1024, full.latencyNanos.sum());
  }

  @Test
  @Timeout(10)
  void concurrentSelectionDoesNotChangeOutcomeTotalsOrScaleDurations() throws Exception {
    GeneratorMetrics metrics = new GeneratorMetrics(1024);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int thread = 0; thread < 8; thread++) {
        jobs.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 1000; i++) {
                    switch (i % 4) {
                      case 0:
                        metrics.completed(8, true);
                        break;
                      case 1:
                        metrics.completedWithoutLatency(true);
                        break;
                      case 2:
                        metrics.failed(IdException.Reason.TIMEOUT);
                        metrics.completed(16, false);
                        break;
                      default:
                        metrics.failed(IdException.Reason.CLOSED);
                        metrics.completedWithoutLatency(false);
                    }
                  }
                }));
      }
      for (Future<?> job : jobs) {
        job.get(5, TimeUnit.SECONDS);
      }
      assertEquals(4000, metrics.generated());
      assertEquals(2000, metrics.failures().get(IdException.Reason.TIMEOUT));
      assertEquals(2000, metrics.failures().get(IdException.Reason.CLOSED));
      assertEquals(4000, metrics.latencySamples());
      assertEquals(48000, metrics.latencyNanos.sum());
      assertEquals(8, metrics.percentile(0.50));
      assertEquals(16, metrics.percentile(0.99));
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
