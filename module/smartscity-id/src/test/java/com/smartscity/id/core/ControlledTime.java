package com.smartscity.id.core;

import com.smartscity.id.spi.TimeSource;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One coherent virtual instant per nano/wall/nano sample, including concurrent readers.
 *
 * @author jack
 */
final class ControlledTime implements TimeSource {
  static final long BASE = BitsAllocator.EPOCH.toEpochMilli() + 100_000;

  private static final class Instant {
    final long wall;
    final long nanos;

    Instant(long wall, long nanos) {
      this.wall = wall;
      this.nanos = nanos;
    }
  }

  private final AtomicReference<Instant> now = new AtomicReference<>(new Instant(BASE, 0));
  private final ThreadLocal<Instant> sampled = new ThreadLocal<>();

  void set(long wall, long nanos) {
    now.set(new Instant(wall, nanos));
  }

  public long nanoTime() {
    Instant instant = sampled.get();
    if (instant == null) {
      instant = now.get();
      sampled.set(instant);
    } else {
      sampled.remove();
    }
    return instant.nanos;
  }

  public long wallTimeMillis() {
    Instant instant = sampled.get();
    if (instant == null) {
      throw new AssertionError("Expected nano/wall/nano sampling");
    }
    return instant.wall;
  }
}
