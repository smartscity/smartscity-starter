package com.smartscity.id.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SPMC bounded queue. A stalled claimed consumer can stall the next producer lap.
 *
 * @author jack
 */
public final class RingBuffer {
  private static final VarHandle SEQUENCE = MethodHandles.arrayElementVarHandle(long[].class);
  private final long[] ids;
  private final long[] sequence;

  private final int mask;
  private final PaddedCursor head = new PaddedCursor();
  private final PaddedCursor tail = new PaddedCursor();

  /**
   * Creates a bounded single-producer, multiple-consumer ring.
   *
   * @param capacity power of two from 2 through 2^24
   * @throws IllegalArgumentException if the capacity is unsupported
   */
  public RingBuffer(int capacity) {
    if (capacity < 2 || capacity > (1 << 24) || Integer.bitCount(capacity) != 1) {
      throw new IllegalArgumentException("Ring capacity must be a power of two in [2, 2^24]");
    }
    ids = new long[capacity];
    sequence = new long[capacity];
    mask = capacity - 1;
    for (int i = 0; i < capacity; i++) {
      sequence[i] = i;
    }
  }

  /**
   * Publishes one ID; only the single producer may call this method.
   *
   * @param id positive encoded ID
   * @return false when the next slot is still owned by a consumer
   * @throws IllegalArgumentException if id is nonpositive
   * @throws IllegalStateException before the ring cursor would overflow
   */
  public boolean offer(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("ID must be positive");
    }
    long p = tail.get();
    if (p >= Long.MAX_VALUE - ids.length) {
      throw new IllegalStateException("Ring position exhausted");
    }
    int index = (int) p & mask;
    if ((long) SEQUENCE.getAcquire(sequence, index) != p) {
      return false;
    }
    ids[index] = id;
    SEQUENCE.setRelease(sequence, index, p + 1);
    tail.lazySet(p + 1);
    return true;
  }

  /**
   * Attempts up to eight consumer claims; failed CAS never grants ownership.
   *
   * @return a positive ID, or zero when no claim was obtained
   */
  public long poll() {
    for (int attempt = 0; attempt < 8; attempt++) {
      long c = head.get();
      int index = (int) c & mask;
      if ((long) SEQUENCE.getAcquire(sequence, index) != c + 1) {
        return 0;
      }
      if (head.compareAndSet(c, c + 1)) {
        long id = ids[index];
        SEQUENCE.setRelease(sequence, index, c + ids.length);
        return id;
      }
      Thread.onSpinWait();
    }
    return 0;
  }

  /**
   * Observes the oldest slot without claiming it or changing readiness.
   *
   * @return the observed ID, or zero if empty or the slot is concurrently recycled
   */
  public long oldestId() {
    long c = head.get();
    int index = (int) c & mask;
    if ((long) SEQUENCE.getAcquire(sequence, index) != c + 1) {
      return 0;
    }
    long id = ids[index];
    return (long) SEQUENCE.getAcquire(sequence, index) == c + 1 ? id : 0;
  }

  /**
   * Estimates occupancy while producer and consumer cursors may be moving.
   *
   * @return occupied slots, clamped to the ring capacity; not an admission or readiness decision
   */
  public int size() {
    return (int) Math.max(0, Math.min(ids.length, tail.get() - head.get()));
  }

  public int capacity() {
    return ids.length;
  }

  // Padding is a layout hint, not a portable cache-line alignment guarantee.
  private static final class PaddedCursor extends AtomicLong {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("unused")
    public volatile long p1;

    @SuppressWarnings("unused")
    public volatile long p2;

    @SuppressWarnings("unused")
    public volatile long p3;

    @SuppressWarnings("unused")
    public volatile long p4;

    @SuppressWarnings("unused")
    public volatile long p5;

    @SuppressWarnings("unused")
    public volatile long p6;

    @SuppressWarnings("unused")
    public volatile long p7;

    @SuppressWarnings("unused")
    public volatile long p8;
  }
}
