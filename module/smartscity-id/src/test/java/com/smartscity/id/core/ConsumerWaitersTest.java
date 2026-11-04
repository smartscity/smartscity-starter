package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Checks bounded notification ownership and the publication-before-park interleaving.
 *
 * @author jack
 */
class ConsumerWaitersTest {
  @Test
  void registrationsAreBoundedAndCancellationReleasesCapacity() {
    ConsumerWaiters waiters = new ConsumerWaiters();
    int[] slots = new int[64];
    for (int i = 0; i < slots.length; i++) {
      slots[i] = waiters.register();
      assertTrue(slots[i] >= 0);
    }
    assertEquals(-1, waiters.register());
    for (int slot : slots) {
      waiters.cancel(slot);
    }
    int reused = waiters.register();
    assertTrue(reused >= 0);
    waiters.cancel(reused);
  }

  @Test
  @Timeout(5)
  void signalBeforeParkingLeavesAPermitAndReleasesRegistration() throws Exception {
    ConsumerWaiters waiters = new ConsumerWaiters();
    CountDownLatch registered = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean signaled = new AtomicBoolean();
    Thread consumer =
        new Thread(
            () -> {
              int slot = waiters.register();
              registered.countDown();
              // Do not block through another synchronizer that could consume LockSupport's permit.
              while (!signaled.get() && !Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
              }
              LockSupport.park();
              waiters.cancel(slot);
              done.countDown();
            });
    consumer.start();
    try {
      assertTrue(registered.await(2, TimeUnit.SECONDS));
      waiters.signalAll();
      signaled.set(true);
      assertTrue(done.await(2, TimeUnit.SECONDS));
    } finally {
      consumer.interrupt();
      LockSupport.unpark(consumer);
      consumer.join(1000);
    }
    int slot = waiters.register();
    assertTrue(slot >= 0);
    waiters.cancel(slot);
  }
}
