package com.smartscity.id.core;

import com.smartscity.id.api.IdException;

/** Single owner: only the cache producer may advance this sequence. */
public final class DefaultUniqueIdGenerator {
    private final BitsAllocator bits;
    private final long worker;
    private long second = -1;
    private long nextSequence;
    public DefaultUniqueIdGenerator(BitsAllocator bits, long worker) {
        if (worker < 0 || worker >= bits.getWorkerSlots()) throw new IllegalArgumentException("Invalid worker");
        this.bits = bits; this.worker = worker;
    }
    /** Zero means this second is exhausted, not an ID. Does not borrow future time. */
    public long next(long nowSecond) {
        if (nowSecond < 0 || nowSecond > bits.getMaxTime()) {
            throw new IdException(IdException.Reason.CAPACITY, "Epoch time range exhausted");
        }
        if (nowSecond < second) throw new IdException(IdException.Reason.CLOCK, "ID time regressed");
        if (nowSecond > second) { second = nowSecond; nextSequence = 0; }
        if (second == 0 && worker == 0 && nextSequence == 0) nextSequence = 1;
        if (nextSequence > bits.getMaxSequence()) return 0;
        return bits.allocate(second, worker, nextSequence++);
    }
}
