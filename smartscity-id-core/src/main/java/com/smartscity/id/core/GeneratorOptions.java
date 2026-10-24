package com.smartscity.id.core;

import java.time.Duration;
import java.util.Objects;

public final class GeneratorOptions {
    public final BitsAllocator bits;
    public final int capacity, lowWaterPercent;
    public final long waitNanos, clockToleranceMillis;
    private GeneratorOptions(Builder b) {
        bits = Objects.requireNonNull(b.bits); capacity = b.capacity; lowWaterPercent = b.lowWaterPercent;
        waitNanos = b.wait.toNanos(); clockToleranceMillis = b.clockTolerance.toMillis();
        if (capacity < 2 || capacity > (1 << 24) || Integer.bitCount(capacity) != 1
                || lowWaterPercent < 1 || lowWaterPercent > 99 || waitNanos < 0
                || waitNanos > Duration.ofSeconds(1).toNanos() || clockToleranceMillis < 1) {
            throw new IllegalArgumentException("Invalid cache, wait or clock options");
        }
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private BitsAllocator bits = BitsAllocator.standard();
        private int capacity = 16384, lowWaterPercent = 50;
        private Duration wait = Duration.ofMillis(10), clockTolerance = Duration.ofSeconds(1);
        public Builder bits(BitsAllocator value) { bits = value; return this; }
        public Builder capacity(int value) { capacity = value; return this; }
        public Builder lowWaterPercent(int value) { lowWaterPercent = value; return this; }
        public Builder waitTimeout(Duration value) { wait = value; return this; }
        public Builder clockTolerance(Duration value) { clockTolerance = value; return this; }
        public GeneratorOptions build() { return new GeneratorOptions(this); }
    }
}
