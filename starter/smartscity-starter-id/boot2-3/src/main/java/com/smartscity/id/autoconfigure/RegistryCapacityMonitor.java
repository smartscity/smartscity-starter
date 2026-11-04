package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.RegistryCapacity;

/**
 * Owns demand-driven registry sampling with one in-flight read and no task queue.
 *
 * <p>Snapshot readers never wait for JDBC. A stalled driver prevents further sampling rather than
 * accumulating threads. Closing interrupts the read; its scoped JDBC resources remain owned by the
 * reader until it finishes. Sampling failures never change generator readiness.
 *
 * @author jack
 */
final class RegistryCapacityMonitor implements AutoCloseable {
  @FunctionalInterface
  interface Reader {
    RegistryCapacity read() throws Exception;
  }

  private final Reader reader;
  private final long intervalNanos;
  private RegistryCapacity latest;
  private Thread inFlight;
  private long lastAttempt;
  private boolean attempted;
  private boolean closed;

  RegistryCapacityMonitor(Reader reader, RegistryCapacity initial, long intervalNanos) {
    this.reader = java.util.Objects.requireNonNull(reader);
    latest = java.util.Objects.requireNonNull(initial);
    if (intervalNanos <= 0) {
      throw new IllegalArgumentException("Capacity sampling interval must be positive");
    }
    this.intervalNanos = intervalNanos;
  }

  synchronized RegistryCapacity snapshot() {
    long now = System.nanoTime();
    if (!closed && inFlight == null && (!attempted || now - lastAttempt >= intervalNanos)) {
      attempted = true;
      lastAttempt = now;
      inFlight = new Thread(this::refresh, "smartscity-id-capacity");
      inFlight.setDaemon(true);
      try {
        inFlight.start();
      } catch (RuntimeException e) {
        inFlight = null;
        latest = latest.failed();
      }
    }
    return latest;
  }

  private void refresh() {
    try {
      RegistryCapacity observation = java.util.Objects.requireNonNull(reader.read());
      synchronized (this) {
        if (!closed) {
          latest = observation;
        }
      }
    } catch (Exception e) {
      synchronized (this) {
        if (!closed) {
          latest = latest.failed();
        }
      }
    } finally {
      synchronized (this) {
        inFlight = null;
      }
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (inFlight != null) {
      inFlight.interrupt();
    }
  }
}
