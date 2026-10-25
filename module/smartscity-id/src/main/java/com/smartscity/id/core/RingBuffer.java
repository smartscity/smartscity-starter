package com.smartscity.id.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SPMC bounded queue with batched publication and per-slot reclamation generations.
 *
 * <p>The producer publishes initialized slots through the tail. Consumers release slots only after
 * reading them, so a stalled claimant can stall the next producer lap.
 *
 * @author jack
 */
public final class RingBuffer {
  static final long CONTENDED = -1;
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
    return offerRange(id, 1) == 1;
  }

  /**
   * Publishes a prefix of a validated contiguous ID range with one tail release. The caller owns
   * layout/sequence boundaries; this queue only checks positive longs and available slots.
   */
  int offerRange(long firstId, int count) {
    if (firstId <= 0 || count < 1 || firstId > Long.MAX_VALUE - (count - 1L)) {
      throw new IllegalArgumentException("ID range must contain positive longs without overflow");
    }
    long p = tail.get();
    if (p >= Long.MAX_VALUE - ids.length) {
      throw new IllegalStateException("Ring position exhausted");
    }
    int limit = (int) Math.min(Math.min((long) count, ids.length), Long.MAX_VALUE - ids.length - p);
    int offered = 0;
    while (offered < limit) {
      long position = p + offered;
      int index = (int) position & mask;
      if ((long) SEQUENCE.getAcquire(sequence, index) != position) {
        break;
      }
      ids[index] = firstId + offered;
      offered++;
    }
    if (offered != 0) {
      // No consumer can claim any part of this range before all its payload writes are visible.
      // Slot generations are only reclamation receipts; the producer does not write them.
      tail.lazySet(p + offered);
    }
    return offered;
  }

  /** Only the producer uses this recheck after arming its low-watermark notification. */
  boolean hasReclaimedSlot() {
    long p = tail.get();
    return (long) SEQUENCE.getAcquire(sequence, (int) p & mask) == p;
  }

  /**
   * Attempts up to eight consumer claims; failed CAS never grants ownership.
   *
   * @return a positive ID, or zero when no claim was obtained
   */
  public long poll() {
    long id = pollClaim();
    return id == CONTENDED ? 0 : id;
  }

  /** Returns zero for an observed empty queue and CONTENDED for exhausted CAS retries. */
  long pollClaim() {
    for (int attempt = 0; attempt < 8; attempt++) {
      long c = head.get();
      if (c >= tail.get()) {
        return 0;
      }
      if (head.compareAndSet(c, c + 1)) {
        int index = (int) c & mask;
        // The acquired tail publishes this payload; successful CAS alone grants read ownership.
        long id = ids[index];
        SEQUENCE.setRelease(sequence, index, c + ids.length);
        return id;
      }
      Thread.onSpinWait();
    }
    return CONTENDED;
  }

  /**
   * Observes the oldest slot without claiming it or changing readiness.
   *
   * @return the observed ID, or zero if empty or the slot is concurrently recycled
   */
  public long oldestId() {
    long c = head.get();
    int index = (int) c & mask;
    if (c >= tail.get()) {
      return 0;
    }
    long id = ids[index];
    // A claim may release and recycle this slot during observation. Never report that newer lap.
    VarHandle.acquireFence();
    return head.get() == c ? id : 0;
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
