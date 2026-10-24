package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdParts;
import java.time.Instant;

/** Immutable encoding contract. Values are checked before shifts, never truncated. */
public final class BitsAllocator {
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private final int timeBits, workerBits, sequenceBits;
    private final long maxTime, maxWorker, maxSequence;
    public BitsAllocator(int timeBits, int workerBits, int sequenceBits) {
        if (timeBits < 1 || workerBits < 1 || sequenceBits < 1
                || timeBits > 61 || workerBits > 61 || sequenceBits > 61
                || timeBits + workerBits + sequenceBits > 63) {
            throw new IdException(IdException.Reason.CONFIGURATION, "Invalid ID bit layout");
        }
        this.timeBits = timeBits; this.workerBits = workerBits; this.sequenceBits = sequenceBits;
        maxTime = (1L << timeBits) - 1; maxWorker = (1L << workerBits) - 1;
        maxSequence = (1L << sequenceBits) - 1;
    }
    public static BitsAllocator standard() { return new BitsAllocator(30, 18, 15); }
    public static BitsAllocator highThroughput() { return new BitsAllocator(30, 16, 17); }
    public static BitsAllocator javascript(int sequenceBits) {
        if (sequenceBits < 15 || sequenceBits > 17) {
            throw new IdException(IdException.Reason.CONFIGURATION, "JS sequence bits must be 15..17");
        }
        return new BitsAllocator(43 - sequenceBits, 10, sequenceBits);
    }
    public long allocate(long seconds, long worker, long sequence) {
        if (seconds < 0 || seconds > maxTime || worker < 0 || worker > maxWorker
                || sequence < 0 || sequence > maxSequence) {
            throw new IdException(IdException.Reason.CAPACITY, "ID field exceeds its range");
        }
        long id = (seconds << (workerBits + sequenceBits)) | (worker << sequenceBits) | sequence;
        if (id == 0) throw new IdException(IdException.Reason.CAPACITY, "ID zero is reserved");
        return id;
    }
    public IdParts parse(long id) {
        if (id <= 0 || (timeBits + workerBits + sequenceBits < 63
                && (id >>> (timeBits + workerBits + sequenceBits)) != 0)) {
            throw new IdException(IdException.Reason.CONFIGURATION, "ID does not fit this layout");
        }
        return new IdParts(EPOCH.plusSeconds(id >>> (workerBits + sequenceBits)),
                (id >>> sequenceBits) & maxWorker, id & maxSequence);
    }
    public int getTimeBits() { return timeBits; }
    public int getWorkerBits() { return workerBits; }
    public int getSequenceBits() { return sequenceBits; }
    public long getMaxTime() { return maxTime; }
    public long getMaxSequence() { return maxSequence; }
    public long getWorkerSlots() { return maxWorker + 1; }
    public String signature() { return timeBits + "/" + workerBits + "/" + sequenceBits; }
}
