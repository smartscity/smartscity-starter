package com.smartscity.id.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded, one-shot notifications for cold-path consumers; overflow falls back to timed polling.
 *
 * <p>Consumers register before rechecking the ring. Publication and termination signal registered
 * threads, including those not yet parked. Only the registering thread may cancel its slot.
 *
 * @author jack
 */
final class ConsumerWaiters {
  private final AtomicReferenceArray<Thread> slots = new AtomicReferenceArray<>(64);
  private final AtomicInteger registrations = new AtomicInteger();

  int register() {
    Thread current = Thread.currentThread();
    int start = System.identityHashCode(current) & (slots.length() - 1);
    // Count before publishing the reference so a concurrent signal cannot decrement below zero.
    registrations.incrementAndGet();
    for (int i = 0; i < slots.length(); i++) {
      int index = (start + i) & (slots.length() - 1);
      if (slots.compareAndSet(index, null, current)) {
        return index;
      }
    }
    registrations.decrementAndGet();
    return -1;
  }

  void cancel(int slot) {
    if (slot >= 0 && slots.compareAndSet(slot, Thread.currentThread(), null)) {
      registrations.decrementAndGet();
    }
  }

  void signalAll() {
    if (registrations.get() == 0) {
      return;
    }
    for (int i = 0; i < slots.length(); i++) {
      if (slots.get(i) == null) {
        continue;
      }
      Thread waiter = slots.getAndSet(i, null);
      if (waiter != null) {
        registrations.decrementAndGet();
        LockSupport.unpark(waiter);
      }
    }
  }
}
