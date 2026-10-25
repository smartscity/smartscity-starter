package com.smartscity.id.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable per-generator options; the builder is not thread-safe.
 *
 * @author jack
 */
public final class GeneratorOptions {
  /** Immutable encoding format shared by generation and decoding. */
  public final BitsAllocator bits;

  /** Power-of-two ring capacity, from 2 through 2^24. */
  public final int capacity;

  /** Refill trigger as an occupied-capacity percentage, from 1 through 99. */
  public final int lowWaterPercent;

  /** Maximum caller wait; zero makes empty-cache claims fail immediately. */
  public final long waitNanos;

  /** Positive allowed wall-clock deviation from the monotonic projection. */
  public final long clockToleranceMillis;

  private GeneratorOptions(Builder b) {
    bits = Objects.requireNonNull(b.bits);
    capacity = b.capacity;
    lowWaterPercent = b.lowWaterPercent;
    waitNanos = b.wait.toNanos();
    clockToleranceMillis = b.clockTolerance.toMillis();
    if (capacity < 2 || capacity > (1 << 24) || Integer.bitCount(capacity) != 1) {
      throw new IllegalArgumentException(
          "buffer-size/capacity must be a power of two from 2 through 16777216");
    }
    if (lowWaterPercent < 1 || lowWaterPercent > 99) {
      throw new IllegalArgumentException("low-water-percent must be from 1 through 99");
    }
    if (waitNanos < 0 || waitNanos > Duration.ofSeconds(1).toNanos()) {
      throw new IllegalArgumentException("wait-timeout must be from 0 through 1 second");
    }
    if (clockToleranceMillis < 1) {
      throw new IllegalArgumentException("clock-tolerance must be at least 1 millisecond");
    }
  }

  /**
   * Creates independent mutable options with the standard layout and runtime defaults.
   *
   * @return a builder confined to its calling thread until construction completes
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Mutable construction state confined to one thread; validation occurs in {@link #build()}.
   *
   * @author jack
   */
  public static final class Builder {
    private BitsAllocator bits = BitsAllocator.standard();
    private int capacity = 16384;
    private int lowWaterPercent = 50;

    private Duration wait = Duration.ofMillis(10);
    private Duration clockTolerance = Duration.ofSeconds(1);

    /**
     * Selects the immutable encoding format.
     *
     * @param value allocator containing the epoch and widths; must not be null at build time
     * @return this builder; validation is deferred until {@link #build()}
     */
    public Builder bits(BitsAllocator value) {
      bits = value;
      return this;
    }

    /**
     * Sets the number of preallocated ring slots.
     *
     * @param value power of two from 2 through 16777216
     * @return this builder; validation is deferred until {@link #build()}
     */
    public Builder capacity(int value) {
      capacity = value;
      return this;
    }

    /**
     * Sets the occupied-capacity threshold that wakes the producer.
     *
     * @param value percentage from 1 through 99
     * @return this builder; validation is deferred until {@link #build()}
     */
    public Builder lowWaterPercent(int value) {
      lowWaterPercent = value;
      return this;
    }

    /**
     * Sets the total caller budget for waiting on availability and contention.
     *
     * @param value duration from zero through one second; zero disables waiting
     * @return this builder; validation is deferred until {@link #build()}
     */
    public Builder waitTimeout(Duration value) {
      wait = value;
      return this;
    }

    /**
     * Sets the permitted deviation between wall time and the monotonic projection.
     *
     * @param value duration of at least one millisecond
     * @return this builder; validation is deferred until {@link #build()}
     */
    public Builder clockTolerance(Duration value) {
      clockTolerance = value;
      return this;
    }

    /**
     * Validates the complete configuration and captures an immutable options snapshot.
     *
     * @return options independent of later builder changes
     * @throws NullPointerException if the allocator or either duration is null
     * @throws IllegalArgumentException if capacity, watermark or duration limits are violated
     * @throws ArithmeticException if a duration cannot be represented in its required time unit
     */
    public GeneratorOptions build() {
      return new GeneratorOptions(this);
    }
  }
}
