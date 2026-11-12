package com.smartscity.observability;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reserves bounded body storage before allocation and releases it exactly once.
 *
 * @author jack
 */
public final class CaptureBudget {
  private final long capacity;
  private final AtomicLong reserved = new AtomicLong();

  public CaptureBudget(long capacity) {
    if (capacity < 0) {
      throw new IllegalArgumentException("Negative capture budget");
    }
    this.capacity = capacity;
  }

  /**
   * Reserves storage without waiting for another request to release it.
   *
   * @param limit maximum body bytes
   * @return owned capture, or null on saturation
   */
  public Capture reserve(int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("Invalid body limit");
    }
    long n;
    do {
      n = reserved.get();
      if (limit > capacity - n) {
        return null;
      }
    } while (!reserved.compareAndSet(n, n + limit));
    return new Capture(limit);
  }

  public long reservedBytes() {
    return reserved.get();
  }

  /**
   * A synchronized bounded body capture; its reservation belongs to the exchange.
   *
   * @author jack
   */
  public final class Capture implements AutoCloseable {
    private final int limit;
    private ByteArrayOutputStream bytes;
    private boolean overflow;
    private boolean closed;

    private Capture(int limit) {
      this.limit = limit;
      bytes = new ByteArrayOutputStream(limit);
    }

    public synchronized void append(byte[] b, int off, int len) {
      if (closed || overflow) {
        return;
      }
      if (len > limit - bytes.size()) {
        overflow = true;
        bytes.reset();
        return;
      }
      bytes.write(b, off, len);
    }

    /**
     * Appends the low eight bits without allocating a single-byte array.
     *
     * @param value byte from a successful transport read/write
     */
    public synchronized void append(int value) {
      if (closed || overflow) {
        return;
      }
      if (bytes.size() == limit) {
        overflow = true;
        bytes.reset();
      } else {
        bytes.write(value);
      }
    }

    public synchronized byte[] snapshot() {
      return overflow || closed ? null : bytes.toByteArray();
    }

    public synchronized boolean overflow() {
      return overflow;
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        bytes = null;
        reserved.addAndGet(-limit);
      }
    }
  }
}
