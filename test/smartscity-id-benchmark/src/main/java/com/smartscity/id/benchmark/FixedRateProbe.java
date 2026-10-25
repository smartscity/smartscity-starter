package com.smartscity.id.benchmark;

import com.smartscity.id.api.IdException;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded, fixed schedule with missed arrivals counted, not silently back-pressured away.
 *
 * @author jack
 */
public final class FixedRateProbe {
  private static final int RATE = 100000;
  private static final int THREADS = 4;

  private static final long PERIOD = 1000000000L / RATE;
  private static final long MAX_LAG = 10000000L;

  private FixedRateProbe() {}

  public static void main(String[] args) throws Exception {
    int seconds = args.length == 0 ? 20 : Integer.parseInt(args[0]);
    if (seconds < 1 || seconds > 30) {
      throw new IllegalArgumentException("Measurement must be 1..30 seconds");
    }
    try (CachedUniqueIdGenerator generator = BenchmarkGenerator.create("high-throughput")) {
      run(generator, 5, false);
      run(generator, seconds, true);
    }
  }

  private static void run(CachedUniqueIdGenerator generator, int seconds, boolean report)
      throws Exception {
    int count = RATE * seconds;
    long[] ids = new long[count];
    long[] latency = new long[count];
    long[] scheduledLatency = new long[count];

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<Consumer>> futures = new ArrayList<>();
    long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
    long end = start + TimeUnit.SECONDS.toNanos(seconds);
    try {
      for (int i = 0; i < THREADS; i++) {
        Consumer consumer =
            new Consumer(i, count, seconds, start, end, generator, ids, latency, scheduledLatency);
        futures.add(pool.submit(consumer));
      }
      long successful = 0;
      long timeouts = 0;
      long missed = 0;
      long late = 0;

      long[] perSecond = new long[seconds];
      for (Future<Consumer> future : futures) {
        Consumer c =
            future.get(
                Math.max(1, end + TimeUnit.SECONDS.toNanos(3) - System.nanoTime()),
                TimeUnit.NANOSECONDS);
        successful += c.successful;
        timeouts += c.timeouts;
        missed += c.missed;
        late += c.late;
        for (int s = 0; s < seconds; s++) {
          perSecond[s] += c.perSecond[s];
        }
      }
      if (successful + timeouts + missed + late != count) {
        throw new AssertionError("Unaccounted arrivals");
      }
      int n = 0;
      for (int i = 0; i < count; i++) {
        if (ids[i] != 0) {
          ids[n] = ids[i];
          latency[n] = latency[i];
          scheduledLatency[n++] = scheduledLatency[i];
        }
      }
      Arrays.sort(ids, 0, n);
      long duplicates = 0;
      for (int i = 1; i < n; i++) {
        if (ids[i] == ids[i - 1]) {
          duplicates++;
        }
      }
      Arrays.sort(latency, 0, n);
      Arrays.sort(scheduledLatency, 0, n);
      if (report) {
        System.out.printf(
            Locale.ROOT,
            "{\"seconds\":%d,\"targetTps\":%d,\"threads\":%d,\"scheduled\":%d,\"attempted\":%d,\"successful\":%d,\"timeouts\":%d,\"missed\":%d,\"late\":%d,\"duplicates\":%d,\"successfulTps\":%.2f,\"successPerSecond\":%s,\"callP50Us\":%.3f,\"callP95Us\":%.3f,\"callP99Us\":%.3f,\"scheduledP99Us\":%.3f,\"targetMet\":%s}%n",
            seconds,
            RATE,
            THREADS,
            count,
            successful + timeouts + late,
            successful,
            timeouts,
            missed,
            late,
            duplicates,
            (double) successful / seconds,
            Arrays.toString(perSecond),
            percentile(latency, n, .50),
            percentile(latency, n, .95),
            percentile(latency, n, .99),
            percentile(scheduledLatency, n, .99),
            successful == count && duplicates == 0);
      }
      if (duplicates != 0) {
        throw new AssertionError("Duplicate IDs: " + duplicates);
      }
    } finally {
      pool.shutdownNow();
      if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Consumers did not stop");
      }
    }
  }

  private static double percentile(long[] samples, int n, double p) {
    return n == 0 ? 0 : samples[Math.min(n - 1, (int) Math.ceil(n * p) - 1)] / 1000.0;
  }

  /** Each consumer exclusively owns its counters and every fourth array slot. */
  private static final class Consumer implements Callable<Consumer> {
    final int first;
    final int count;

    final long start;
    final long end;

    final CachedUniqueIdGenerator generator;
    final long[] ids;
    final long[] latency;
    final long[] scheduledLatency;
    final long[] perSecond;

    long successful;
    long timeouts;
    long missed;
    long late;

    Consumer(
        int first,
        int count,
        int seconds,
        long start,
        long end,
        CachedUniqueIdGenerator generator,
        long[] ids,
        long[] latency,
        long[] scheduledLatency) {
      this.first = first;
      this.count = count;
      this.start = start;
      this.end = end;
      this.generator = generator;
      this.ids = ids;
      this.latency = latency;
      this.scheduledLatency = scheduledLatency;
      perSecond = new long[seconds];
    }

    public Consumer call() {
      for (int index = first; index < count; index += THREADS) {
        if (Thread.currentThread().isInterrupted()) {
          throw new CancellationException();
        }
        long due = start + index * PERIOD;
        long now;
        while ((now = System.nanoTime()) < due) {
          if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
          }
          LockSupport.parkNanos(due - now);
        }
        if (now >= end || now - due > MAX_LAG) {
          missed++;
          continue;
        }
        try {
          long id = generator.getUniqueId();
          long finished = System.nanoTime();
          // Results finishing beyond the measurement window count as missed, not achieved TPS.
          ids[index] = id;
          latency[index] = finished - now;
          scheduledLatency[index] = finished - due;
          if (finished >= end) {
            late++;
            continue;
          }
          successful++;
          perSecond[(int) ((finished - start) / 1_000_000_000L)]++;
        } catch (IdException e) {
          if (e.getReason() != IdException.Reason.TIMEOUT) {
            throw e;
          }
          timeouts++;
        }
      }
      return this;
    }
  }
}
