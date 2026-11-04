package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects exact outcomes and sampled durations without a thread registry or per-call allocation.
 *
 * @author jack
 */
final class GeneratorMetrics {
  final LongAdder emptyPolls = new LongAdder();
  final LongAdder contendedPolls = new LongAdder();
  final LongAdder consumerParks = new LongAdder();
  final LongAdder emptyTimeouts = new LongAdder();
  final LongAdder contentionTimeouts = new LongAdder();
  final LongAdder deadlineTimeouts = new LongAdder();
  final LongAdder waits = new LongAdder();
  final LongAdder waitNanos = new LongAdder();

  final LongAdder latencyNanos = new LongAdder();
  final LongAdder refillNanos = new LongAdder();
  final LongAdder refillFailures = new LongAdder();

  // Sampled successes contribute through their histogram; other successes use one exact counter.
  private final LongAdder unsampledSuccesses = new LongAdder();
  private final int sampleMask;
  private final LongAdder[] successfulLatencyBuckets = new LongAdder[64];
  private final LongAdder[] failedLatencyBuckets = new LongAdder[64];
  private final EnumMap<IdException.Reason, LongAdder> failures =
      new EnumMap<>(IdException.Reason.class);

  GeneratorMetrics() {
    this(1);
  }

  GeneratorMetrics(int sampleInterval) {
    sampleMask = sampleInterval - 1;
    for (IdException.Reason r : IdException.Reason.values()) {
      failures.put(r, new LongAdder());
    }
    for (int i = 0; i < successfulLatencyBuckets.length; i++) {
      successfulLatencyBuckets[i] = new LongAdder();
      failedLatencyBuckets[i] = new LongAdder();
    }
  }

  /** Selection precedes the outcome, so failures are not overrepresented in the sample. */
  boolean sampleLatency() {
    return sampleMask == 0 || (ThreadLocalRandom.current().nextInt() & sampleMask) == 0;
  }

  void completedWithoutLatency(boolean generated) {
    if (generated) {
      unsampledSuccesses.increment();
    }
  }

  long latencySamples() {
    long count = 0;
    for (int i = 0; i < successfulLatencyBuckets.length; i++) {
      count += successfulLatencyBuckets[i].sum() + failedLatencyBuckets[i].sum();
    }
    return count;
  }

  void completed(long nanos, boolean generated) {
    latencyNanos.add(nanos);
    int bucket = nanos <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(nanos - 1);
    LongAdder[] buckets = generated ? successfulLatencyBuckets : failedLatencyBuckets;
    buckets[Math.min(63, bucket)].increment();
  }

  long generated() {
    long count = unsampledSuccesses.sum();
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
