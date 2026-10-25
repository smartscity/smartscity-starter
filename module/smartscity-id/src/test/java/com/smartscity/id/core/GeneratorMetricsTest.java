package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
