package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class GeneratorMetrics {
    final LongAdder generated = new LongAdder(), waits = new LongAdder(), waitNanos = new LongAdder();
    final LongAdder latencyNanos = new LongAdder(), refillNanos = new LongAdder(), refillFailures = new LongAdder();
    private final LongAdder[] latencyBuckets = new LongAdder[64];
    private final EnumMap<IdException.Reason, LongAdder> failures = new EnumMap<>(IdException.Reason.class);
    GeneratorMetrics() {
        for (IdException.Reason r : IdException.Reason.values()) failures.put(r, new LongAdder());
        for (int i = 0; i < latencyBuckets.length; i++) latencyBuckets[i] = new LongAdder();
    }
    void latency(long nanos) {
        latencyNanos.add(nanos);
        int bucket = nanos <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(nanos - 1);
        latencyBuckets[Math.min(63, bucket)].increment();
    }
    long percentile(double quantile) {
        long[] counts = new long[64]; long count = 0;
        for (int i = 0; i < counts.length; i++) { counts[i] = latencyBuckets[i].sum(); count += counts[i]; }
        if (count == 0) return 0;
        long target = (long) Math.ceil(count * quantile), cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            cumulative += counts[i];
            if (cumulative >= target) return i == 63 ? Long.MAX_VALUE : 1L << i;
        }
        return Long.MAX_VALUE;
    }
    void failed(IdException.Reason reason) { failures.get(reason).increment(); }
    Map<IdException.Reason, Long> failures() {
        EnumMap<IdException.Reason, Long> result = new EnumMap<>(IdException.Reason.class);
        failures.forEach((key, value) -> result.put(key, value.sum())); return result;
    }
}
