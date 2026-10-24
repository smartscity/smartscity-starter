package com.smartscity.id.api;

public final class IdException extends RuntimeException {
    public enum Reason {
        CONFIGURATION, STARTUP, CLOCK, CAPACITY, TIMEOUT, INTERRUPTED,
        COORDINATION, CLOSED, INTERNAL
    }
    private final Reason reason;
    public IdException(Reason reason, String message) { super(message); this.reason = reason; }
    public IdException(Reason reason, String message, Throwable cause) {
        super(message, cause); this.reason = reason;
    }
    public Reason getReason() { return reason; }
}
