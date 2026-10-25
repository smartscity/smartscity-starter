package com.smartscity.id.core;

import com.smartscity.id.spi.TimeSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds only the background producer so cache-empty failure tests do not depend on sequence limits.
 *
 * @author jack
 */
final class PausedProducerTime implements TimeSource, AutoCloseable {
  private final TimeSource delegate;
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch released = new CountDownLatch(1);

  PausedProducerTime(TimeSource delegate) {
    this.delegate = delegate;
  }

  void awaitPaused() throws InterruptedException {
    if (!entered.await(2, TimeUnit.SECONDS)) {
      throw new AssertionError("Producer did not reach its clock check");
    }
  }

  @Override
  public long nanoTime() {
    if (Thread.currentThread().getName().startsWith("smartscity-id-fill-")) {
      entered.countDown();
      try {
        if (!released.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Producer release timed out");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
    return delegate.nanoTime();
  }

  @Override
  public long wallTimeMillis() {
    return delegate.wallTimeMillis();
  }

  @Override
  public void close() {
    released.countDown();
  }
}
