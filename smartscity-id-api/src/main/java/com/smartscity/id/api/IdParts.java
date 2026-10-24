package com.smartscity.id.api;

import java.time.Instant;

public final class IdParts {
    private final Instant timestamp;
    private final long workerId;
    private final long sequence;
    public IdParts(Instant timestamp, long workerId, long sequence) {
        this.timestamp = timestamp; this.workerId = workerId; this.sequence = sequence;
    }
    public Instant getTimestamp() { return timestamp; }
    public long getWorkerId() { return workerId; }
    public long getSequence() { return sequence; }
}
