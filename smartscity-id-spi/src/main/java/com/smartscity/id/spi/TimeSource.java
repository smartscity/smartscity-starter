package com.smartscity.id.spi;

public interface TimeSource {
    long wallTimeMillis();
    long nanoTime();
    TimeSource SYSTEM = new TimeSource() {
        public long wallTimeMillis() { return System.currentTimeMillis(); }
        public long nanoTime() { return System.nanoTime(); }
    };
}
