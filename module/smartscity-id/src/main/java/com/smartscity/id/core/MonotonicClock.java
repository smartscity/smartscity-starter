package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.TimeSource;
import java.util.Objects;

/**
 * Validates wall-clock drift against one monotonic anchor shared by producer and consumers.
 *
 * @author jack
 */
public final class MonotonicClock {
  private static final long MAX_SAMPLE_NANOS = 1_000_000;
  private static final int SAMPLE_ATTEMPTS = 3;
  private final TimeSource source;
  private final long wallAnchor;
  private final long nanoAnchor;
  private final long toleranceMillis;

  private volatile long deviationMillis;

  /**
   * Captures one wall/monotonic anchor without subsequent re-anchoring.
   *
   * @param source thread-safe clock source
   * @param toleranceMillis maximum permitted wall-clock deviation, at least one millisecond
   * @throws NullPointerException if source is null
   * @throws IllegalArgumentException if the tolerance is nonpositive
   * @throws IdException if a stable monotonic sample cannot be obtained
   */
  public MonotonicClock(TimeSource source, long toleranceMillis) {
    this.source = Objects.requireNonNull(source);
    if (toleranceMillis < 1) {
      throw new IllegalArgumentException("Clock tolerance must be positive");
    }
    this.toleranceMillis = toleranceMillis;
    long anchorNanos = 0;
    long anchorWall = 0;

    boolean sampled = false;
    for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
      long before = source.nanoTime();
      long wall = source.wallTimeMillis();
      long after = source.nanoTime();

      long span = after - before;
      if (span < 0) {
        throw invalidClock("Monotonic clock regressed");
      }
      if (span > MAX_SAMPLE_NANOS) {
        continue;
      }
      anchorNanos = before + span / 2;
      anchorWall = wall;
      sampled = true;
      break;
    }
    if (!sampled) {
      throw invalidClock("Cannot obtain a stable clock anchor");
    }
    nanoAnchor = anchorNanos;
    wallAnchor = anchorWall;
  }

  /**
   * Validates a fresh sample and projects UTC milliseconds from the original anchor.
   *
   * @return projected milliseconds since the Unix epoch
   * @throws IdException on clock regression, excessive drift, unstable sampling or overflow
   */
  public long millis() {
    for (int attempt = 0; attempt < SAMPLE_ATTEMPTS; attempt++) {
      long before = source.nanoTime();
      long wall = source.wallTimeMillis();
      long after = source.nanoTime();

      long span = after - before;
      long elapsed = before - nanoAnchor;

      if (span < 0 || elapsed < 0) {
        throw invalidClock("Monotonic clock regressed");
      }
      // A descheduled/GC-paused pair does not describe one instant. Retry without re-anchoring.
      if (span > MAX_SAMPLE_NANOS) {
        continue;
      }
      try {
        long lower = Math.addExact(wallAnchor, elapsed / 1_000_000);
        long upper = Math.addExact(wallAnchor, (after - nanoAnchor) / 1_000_000);
        long deviation =
            wall < lower
                ? Math.subtractExact(wall, lower)
                : wall > upper ? Math.subtractExact(wall, upper) : 0;
        if (deviationMillis != deviation) {
          deviationMillis = deviation;
        }
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

  private static IdException invalidClock(String message) {
    return new IdException(IdException.Reason.CLOCK, message);
  }

  /**
   * Projects elapsed seconds using the legacy default epoch after validating the clock.
   *
   * @return whole seconds relative to {@link IdLayout#DEFAULT_EPOCH}
   * @throws IdException if clock sampling, drift or arithmetic validation fails
   */
  public long seconds() {
    return seconds(IdLayout.DEFAULT_EPOCH);
  }

  /**
   * Returns validated elapsed whole seconds relative to the supplied epoch.
   *
   * @param epoch identity-format epoch
   * @return elapsed seconds, which may be negative before the epoch
   * @throws IdException if clock validation fails
   * @throws ArithmeticException if subtraction overflows
   */
  public long seconds(java.time.Instant epoch) {
    return Math.subtractExact(Math.floorDiv(millis(), 1000), epoch.getEpochSecond());
  }

  public long getAnchorMillis() {
    return wallAnchor;
  }

  public long getDeviationMillis() {
    return deviationMillis;
  }
}
