package com.smartscity.id.benchmark.comparison;

import com.baidu.fsg.uid.exception.UidGenerateException;
import com.smartscity.id.api.IdException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observes the same external 10 ms completion threshold without changing generator deadlines.
 *
 * <p>This separately timed probe is not a JMH throughput measurement. It counts late successful
 * calls as well as failures, since an implementation without a deadline can succeed after 10 ms.
 *
 * @author jack
 */
public final class DeadlineProbe {
  private static final long BUDGET_NANOS = 10_000_000;

  private DeadlineProbe() {}

  /**
   * Runs a bounded, independently timed completion probe.
   *
   * @param args implementation, profile, thread count and measurement seconds
   * @throws Exception if setup, a worker, bounded shutdown or cleanup fails
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      throw new IllegalArgumentException("Expected implementation, profile, threads and seconds");
    }
    int threads = Integer.parseInt(args[2]);
    int seconds = Integer.parseInt(args[3]);
    if (threads < 1 || threads > 64 || seconds < 1 || seconds > 30) {
      throw new IllegalArgumentException("Threads must be 1..64 and seconds 1..30");
    }
    ComparisonBenchmark.Generator generator = new ComparisonBenchmark.Generator();
    generator.implementation = args[0];
    generator.profile = args[1];
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicLong anchor = new AtomicLong();
    long warmup = TimeUnit.SECONDS.toNanos(1);
    long end = warmup + TimeUnit.SECONDS.toNanos(seconds);
    try (ComparisonBenchmark.Generator owned = generator) {
      owned.start();
      try {
        List<Future<Outcomes>> jobs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          jobs.add(
              pool.submit(
                  () -> {
                    Outcomes outcomes = new Outcomes();
                    ready.countDown();
                    start.await();
                    long origin = anchor.get();
                    while (!Thread.currentThread().isInterrupted()) {
                      long before = System.nanoTime();
                      long sinceStart = before - origin;
                      if (sinceStart >= end) {
                        break;
                      }
                      boolean measured = sinceStart >= warmup;
                      int outcome;
                      try {
                        if (owned.next() <= 0) {
                          throw new AssertionError("Nonpositive ID");
                        }
                        outcome = 0;
                      } catch (IdException e) {
                        if (e.getReason() != IdException.Reason.TIMEOUT) {
                          throw e;
                        }
                        outcome = 1;
                      } catch (UidGenerateException e) {
                        outcome = 2;
                      }
                      if (measured) {
                        outcomes.record(outcome, System.nanoTime() - before);
                      }
                    }
                    return outcomes;
                  }));
        }
        if (!ready.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Probe workers did not start");
        }
        anchor.set(System.nanoTime());
        start.countDown();
        Outcomes total = new Outcomes();
        for (Future<Outcomes> job : jobs) {
          total.add(job.get(seconds + 6L, TimeUnit.SECONDS));
        }
        System.out.printf(
            "{\"implementation\":\"%s\",\"profile\":\"%s\",\"threads\":%d,"
                + "\"measurementSeconds\":%d,\"thresholdNanos\":%d,\"withinBudgetIds\":%d,"
                + "\"lateSuccessfulIds\":%d,\"timeouts\":%d,\"rejections\":%d,"
                + "\"callP99UpperNanos\":%d,\"maxCallNanos\":%d}%n",
            args[0],
            args[1],
            threads,
            seconds,
            BUDGET_NANOS,
            total.withinBudget,
            total.late,
            total.timeouts,
            total.rejections,
            total.percentile99(),
            total.maximum);
      } finally {
        start.countDown();
        pool.shutdownNow();
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Probe workers did not stop");
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** Thread-owned completed-call counts and bounded latency buckets, merged only after joining. */
  private static final class Outcomes {
    private long withinBudget;
    private long late;
    private long timeouts;
    private long rejections;
    private long maximum;
    private final long[] buckets = new long[64];

    void record(int outcome, long nanos) {
      if (outcome == 1) {
        timeouts++;
      } else if (outcome == 2) {
        rejections++;
      } else if (nanos >= BUDGET_NANOS) {
        late++;
      } else {
        withinBudget++;
      }
      maximum = Math.max(maximum, nanos);
      int bucket = nanos <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(nanos - 1);
      buckets[Math.min(63, bucket)]++;
    }

    void add(Outcomes other) {
      withinBudget += other.withinBudget;
      late += other.late;
      timeouts += other.timeouts;
      rejections += other.rejections;
      maximum = Math.max(maximum, other.maximum);
      for (int i = 0; i < buckets.length; i++) {
        buckets[i] += other.buckets[i];
      }
    }

    long percentile99() {
      long count = withinBudget + late + timeouts + rejections;
      if (count == 0) {
        throw new IllegalStateException("No measured attempts");
      }
      long target = (long) Math.ceil(count * 0.99);
      long cumulative = 0;
      for (int i = 0; i < buckets.length; i++) {
        cumulative += buckets[i];
        if (cumulative >= target) {
          return i == 63 ? Long.MAX_VALUE : 1L << i;
        }
      }
      throw new IllegalStateException("Incomplete latency counts");
    }
  }
}
