package com.smartscity.id.benchmark.comparison;

import com.baidu.fsg.uid.exception.UidGenerateException;
import com.smartscity.id.api.IdException;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Bounded concurrent uniqueness and timestamp observation outside the timed JMH path.
 *
 * @author jack
 */
public final class ComparisonProbe {
  private ComparisonProbe() {}

  public static void main(String[] args) throws Exception {
    ComparisonBenchmark.Generator generator = new ComparisonBenchmark.Generator();
    generator.implementation = args[0];
    generator.profile = "standard";
    int threads = 4;
    int attempts = 25000;
    long[][] ids = new long[threads][attempts];
    long[][] latency = new long[threads][attempts];
    int[] successes = new int[threads];
    long[] ahead = new long[threads];
    String[] firstFailure = new String[threads];
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    long epoch = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
    try (ComparisonBenchmark.Generator owned = generator) {
      owned.start();
      Future<?>[] tasks = new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        final int index = t;
        tasks[t] =
            executor.submit(
                () -> {
                  ready.countDown();
                  try {
                    start.await();
                    for (int i = 0; i < attempts; i++) {
                      long before = System.nanoTime();
                      try {
                        long id = owned.next();
                        long elapsed = System.nanoTime() - before;
                        if (id <= 0 || ((id >>> 15) & ((1 << 18) - 1)) != 1) {
                          throw new AssertionError("Invalid ID or worker: " + id);
                        }
                        int slot = successes[index]++;
                        ids[index][slot] = id;
                        latency[index][slot] = elapsed;
                        ahead[index] =
                            Math.max(
                                ahead[index],
                                epoch + (id >>> 33) - System.currentTimeMillis() / 1000);
                      } catch (UidGenerateException e) {
                        if (firstFailure[index] == null) {
                          firstFailure[index] = e.toString().replace('"', '\'').replace('\n', ' ');
                        }
                      } catch (IdException e) {
                        if (e.getReason() != IdException.Reason.TIMEOUT) {
                          throw e;
                        }
                        if (firstFailure[index] == null) {
                          firstFailure[index] = e.getReason().name();
                        }
                      }
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                  }
                });
      }
      if (!ready.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Workers did not start");
      }
      start.countDown();
      for (Future<?> task : tasks) {
        task.get(20, TimeUnit.SECONDS);
      }
      int count = Arrays.stream(successes).sum();
      if (count == 0) {
        throw new AssertionError("No successful IDs");
      }
      long[] all = new long[count];
      long[] times = new long[count];
      int offset = 0;
      for (int t = 0; t < threads; t++) {
        System.arraycopy(ids[t], 0, all, offset, successes[t]);
        System.arraycopy(latency[t], 0, times, offset, successes[t]);
        offset += successes[t];
      }
      Arrays.sort(all);
      Arrays.sort(times);
      int duplicates = 0;
      for (int i = 1; i < count; i++) {
        if (all[i] == all[i - 1]) {
          duplicates++;
        }
      }
      System.out.printf(
          "{\"implementation\":\"%s\",\"attempts\":%d,\"ids\":%d,"
              + "\"failures\":%d,\"duplicates\":%d,\"maxFutureSeconds\":%d,"
              + "\"successfulCallP50Ns\":%d,\"successfulCallP99Ns\":%d,"
              + "\"firstFailures\":\"%s\"}%n",
          args[0],
          threads * attempts,
          count,
          threads * attempts - count,
          duplicates,
          Arrays.stream(ahead).max().orElse(0),
          times[(count - 1) / 2],
          times[(int) ((count - 1) * .99)],
          Arrays.toString(firstFailure));
      if (duplicates != 0) {
        throw new AssertionError("Duplicate IDs observed");
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Probe workers did not stop");
      }
    }
  }
}
