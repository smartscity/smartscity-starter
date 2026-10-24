package com.smartscity.id.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/** SPMC bounded queue. A stalled claimed consumer can stall the next producer lap. */
public final class RingBuffer {
    private static final VarHandle SEQUENCE = MethodHandles.arrayElementVarHandle(long[].class);
    private final long[] ids, sequence;
    private final int mask;
    private final PaddedCursor head = new PaddedCursor();
    private final PaddedCursor tail = new PaddedCursor();
    public RingBuffer(int capacity) {
        if (capacity < 2 || capacity > (1 << 24) || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Ring capacity must be a power of two in [2, 2^24]");
        }
        ids = new long[capacity]; sequence = new long[capacity]; mask = capacity - 1;
        for (int i = 0; i < capacity; i++) sequence[i] = i;
    }
    public boolean offer(long id) {
        if (id <= 0) throw new IllegalArgumentException("ID must be positive");
        long p = tail.get();
        if (p >= Long.MAX_VALUE - ids.length) throw new IllegalStateException("Ring position exhausted");
        int index = (int) p & mask;
        if ((long) SEQUENCE.getAcquire(sequence, index) != p) return false;
        ids[index] = id;
        SEQUENCE.setRelease(sequence, index, p + 1);
        tail.lazySet(p + 1);
        return true;
    }
    /** One bounded claim attempt. Failed CAS never grants ownership. */
    public long poll() {
        long c = head.get();
        int index = (int) c & mask;
        if ((long) SEQUENCE.getAcquire(sequence, index) != c + 1 || !head.compareAndSet(c, c + 1)) return 0;
        long id = ids[index];
        SEQUENCE.setRelease(sequence, index, c + ids.length);
        return id;
    }
    /** Approximate observation only; a concurrently recycled slot is ignored. */
    public long oldestId() {
        long c = head.get(); int index = (int) c & mask;
        if ((long) SEQUENCE.getAcquire(sequence, index) != c + 1) return 0;
        long id = ids[index];
        return (long) SEQUENCE.getAcquire(sequence, index) == c + 1 ? id : 0;
    }
    public int size() { return (int) Math.max(0, Math.min(ids.length, tail.get() - head.get())); }
    public int capacity() { return ids.length; }
    // Padding is a layout hint, not a portable cache-line alignment guarantee.
    private static final class PaddedCursor extends AtomicLong {
        private static final long serialVersionUID = 1L;
        @SuppressWarnings("unused") public volatile long p1, p2, p3, p4, p5, p6, p7, p8;
    }
}
