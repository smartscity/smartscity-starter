package com.smartscity.id.spi;

/**
 * Supplies wall and monotonic time for production clocks and deterministic fault tests.
 *
 * @author jack
 */
public interface TimeSource {
  /**
   * Reads wall time for anchoring and drift validation. Implementations must be thread-safe.
   *
   * @return UTC milliseconds since the Unix epoch; the value may move backwards
   */
  long wallTimeMillis();

  /**
   * Reads elapsed-time ticks for interval measurement. Implementations must be thread-safe.
   *
   * @return nanosecond ticks with an arbitrary origin, like {@link System#nanoTime()}
   */
  long nanoTime();

  TimeSource SYSTEM =
      new TimeSource() {
        @Override
        public long wallTimeMillis() {
          return System.currentTimeMillis();
        }

        @Override
        public long nanoTime() {
          return System.nanoTime();
        }
      };
}
