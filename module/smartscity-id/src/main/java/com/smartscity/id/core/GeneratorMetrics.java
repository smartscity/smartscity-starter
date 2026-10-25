package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects bounded counters and latency buckets without allocating per successful request.
 *
 * @author jack
 */
final class GeneratorMetrics {
  final LongAdder waits = new LongAdder();
  final LongAdder waitNanos = new LongAdder();

  final LongAdder latencyNanos = new LongAdder();
  final LongAdder refillNanos = new LongAdder();
  final LongAdder refillFailures = new LongAdder();

  // The successful histogram is also the generated counter. Keep failed durations separate so
  // every request contributes to latency without an additional success-path atomic increment.
  private final LongAdder[] successfulLatencyBuckets = new LongAdder[64];
  private final LongAdder[] failedLatencyBuckets = new LongAdder[64];
  private final EnumMap<IdException.Reason, LongAdder> failures =
      new EnumMap<>(IdException.Reason.class);

  GeneratorMetrics() {
    for (IdException.Reason r : IdException.Reason.values()) {
      failures.put(r, new LongAdder());
    }
    for (int i = 0; i < successfulLatencyBuckets.length; i++) {
      successfulLatencyBuckets[i] = new LongAdder();
      failedLatencyBuckets[i] = new LongAdder();
    }
  }

  void completed(long nanos, boolean generated) {
    latencyNanos.add(nanos);
    int bucket = nanos <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(nanos - 1);
    LongAdder[] buckets = generated ? successfulLatencyBuckets : failedLatencyBuckets;
    buckets[Math.min(63, bucket)].increment();
  }

  long generated() {
    long count = 0;
    for (LongAdder bucket : successfulLatencyBuckets) {
      count += bucket.sum();
    }
    return count;
  }

  long percentile(double quantile) {
    long[] counts = new long[64];
    long count = 0;
    for (int i = 0; i < counts.length; i++) {
      counts[i] = successfulLatencyBuckets[i].sum() + failedLatencyBuckets[i].sum();
      count += counts[i];
    }
    if (count == 0) {
      return 0;
    }
    long target = (long) Math.ceil(count * quantile);
    long cumulative = 0;

    for (int i = 0; i < counts.length; i++) {
      cumulative += counts[i];
      if (cumulative >= target) {
        return i == 63 ? Long.MAX_VALUE : 1L << i;
      }
    }
    return Long.MAX_VALUE;
  }

  void failed(IdException.Reason reason) {
    failures.get(reason).increment();
  }

  Map<IdException.Reason, Long> failures() {
    EnumMap<IdException.Reason, Long> result = new EnumMap<>(IdException.Reason.class);
    failures.forEach((key, value) -> result.put(key, value.sum()));
    return result;
  }
}
