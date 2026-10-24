package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.TimeSource;
import java.util.Objects;

public final class MonotonicClock {
    private static final long MAX_SAMPLE_NANOS = 1_000_000;
    private static final int SAMPLE_ATTEMPTS = 3;
    private final TimeSource source;
    private final long wallAnchor, nanoAnchor, toleranceMillis;
    private volatile long deviationMillis;
    public MonotonicClock(TimeSource source, long toleranceMillis) {
        this.source = Objects.requireNonNull(source);
        if (toleranceMillis < 1) throw new IllegalArgumentException("Clock tolerance must be positive");
        this.toleranceMillis = toleranceMillis;
        long anchorNanos = 0, anchorWall = 0;
        boolean sampled = false;
        for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
            long before = source.nanoTime(), wall = source.wallTimeMillis(), after = source.nanoTime();
            long span = after - before;
            if (span < 0) throw invalidClock("Monotonic clock regressed");
            if (span > MAX_SAMPLE_NANOS) continue;
            anchorNanos = before + span / 2; anchorWall = wall; sampled = true; break;
        }
        if (!sampled) throw invalidClock("Cannot obtain a stable clock anchor");
        nanoAnchor = anchorNanos; wallAnchor = anchorWall;
    }
    public long millis() {
        for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
            long before = source.nanoTime(), wall = source.wallTimeMillis(), after = source.nanoTime();
            long span = after - before, elapsed = before - nanoAnchor;
            if (span < 0 || elapsed < 0) throw invalidClock("Monotonic clock regressed");
            // A descheduled/GC-paused pair does not describe one instant. Retry without re-anchoring.
            if (span > MAX_SAMPLE_NANOS) continue;
            try {
                long lower = Math.addExact(wallAnchor, elapsed / 1_000_000);
                long upper = Math.addExact(wallAnchor, (after - nanoAnchor) / 1_000_000);
                long deviation = wall < lower ? Math.subtractExact(wall, lower)
                        : wall > upper ? Math.subtractExact(wall, upper) : 0;
                deviationMillis = deviation;
                if (deviation > toleranceMillis || deviation < -toleranceMillis) {
                    throw invalidClock("Wall/monotonic clock deviation exceeds tolerance");
                }
                return upper;
            } catch (ArithmeticException e) {
                throw new IdException(IdException.Reason.CLOCK, "Clock arithmetic overflow", e);
            }
        }
        throw invalidClock("Cannot obtain a stable clock sample");
    }
    private static IdException invalidClock(String message) { return new IdException(IdException.Reason.CLOCK, message); }
    public long seconds() { return Math.floorDiv(millis(), 1000) - BitsAllocator.EPOCH.getEpochSecond(); }
    public long getAnchorMillis() { return wallAnchor; }
    public long getDeviationMillis() { return deviationMillis; }
}
