package com.smartscity.id.spi;

/** Local observation only: state() must never perform network I/O. */
public interface WorkerLease extends AutoCloseable {
    enum State { ACTIVE, SUSPENDED, EXPIRED, CLOSED }
    long rawId();
    long workerId();
    State state();
    @Override void close();
}
