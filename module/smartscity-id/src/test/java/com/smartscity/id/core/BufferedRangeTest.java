package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Protects batched publication, partial acceptance and encoding boundaries without runtime hooks.
 *
 * @author jack
 */
class BufferedRangeTest {
  @Test
  void rangesRespectFullCapacityAndReuseOnlyReleasedSlotsAcrossWrap() {
    RingBuffer ring = new RingBuffer(4);
    assertEquals(4, ring.offerRange(10, 6));
    assertEquals(4, ring.size());
    assertEquals(10, ring.oldestId());
    assertFalse(ring.hasReclaimedSlot());
    assertEquals(0, ring.offerRange(20, 2));
    assertEquals(10, ring.poll());
    assertEquals(11, ring.poll());
    assertTrue(ring.hasReclaimedSlot());
    assertEquals(2, ring.offerRange(20, 4));
    for (long expected : new long[] {12, 13, 20, 21}) {
      assertEquals(expected, ring.oldestId());
      assertEquals(expected, ring.pollClaim());
    }
    assertEquals(0, ring.pollClaim());
    assertEquals(0, ring.oldestId());
    assertEquals(0, ring.size());
  }

  @Test
  void invalidOrOverflowingRangesCannotPublishAPartialPrefix() {
    RingBuffer ring = new RingBuffer(4);
    assertThrows(IllegalArgumentException.class, () -> ring.offerRange(0, 1));
    assertThrows(IllegalArgumentException.class, () -> ring.offerRange(1, 0));
    assertThrows(IllegalArgumentException.class, () -> ring.offerRange(1, -1));
    assertThrows(IllegalArgumentException.class, () -> ring.offerRange(Long.MAX_VALUE, 2));
    assertEquals(0, ring.size());
    assertEquals(0, ring.poll());
    assertEquals(2, ring.offerRange(Long.MAX_VALUE - 1, 2));
    assertEquals(Long.MAX_VALUE - 1, ring.poll());
    assertEquals(Long.MAX_VALUE, ring.poll());
    assertEquals(0, ring.poll());
  }

  @Test
  void batchedCursorMatchesScalarEncodingAcrossLogicalSecondsAndWallTimeJumps() {
    List<BitsAllocator> layouts = new ArrayList<>(ClockRollbackTest.layouts());
    layouts.addAll(Arrays.asList(new BitsAllocator(10, 2, 1), new BitsAllocator(10, 2, 3)));
    for (BitsAllocator bits : layouts) {
      for (long worker : new long[] {0, bits.getWorkerSlots() - 1}) {
        DefaultUniqueIdGenerator source = new DefaultUniqueIdGenerator(bits, worker);
        RingBuffer ring = new RingBuffer(32);
        long second = 0;
        long sequence = worker == 0 ? 1 : 0;
        for (long wall : new long[] {0, 0, 100, 0, 0}) {
          if (wall > second) {
            second = wall;
            sequence = 0;
          }
          // A non-power-of-two batch crosses both slot and small-layout sequence boundaries.
          assertEquals(23, source.fillBuffered(ring, wall, 23));
          for (int i = 0; i < 23; i++) {
            if (sequence > bits.getMaxSequence()) {
              second++;
              sequence = 0;
            }
            assertEquals(bits.allocate(second, worker, sequence++), ring.poll());
          }
          assertEquals(0, ring.poll());
        }
      }
    }
  }

  @Test
  void partialAcceptanceDoesNotReserveTheUnpublishedSuffix() {
    BitsAllocator bits = new BitsAllocator(10, 2, 4);
    DefaultUniqueIdGenerator source = new DefaultUniqueIdGenerator(bits, 1);
    RingBuffer ring = new RingBuffer(4);
    assertEquals(4, source.fillBuffered(ring, 10, 7));
    assertEquals(0, source.fillBuffered(ring, 10, 0));
    assertEquals(0, source.fillBuffered(ring, 10, 7));
    for (int sequence = 0; sequence < 4; sequence++) {
      assertEquals(bits.allocate(10, 1, sequence), ring.poll());
    }
    assertEquals(bits.allocate(10, 1, 4), source.nextBuffered(10));
    assertEquals(3, source.fillBuffered(ring, 10, 3));
    for (int sequence = 5; sequence < 8; sequence++) {
      assertEquals(bits.allocate(10, 1, sequence), ring.poll());
    }
    assertEquals(bits.allocate(10, 1, 8), source.next(10));
  }

  @Test
  void lastLogicalSecondEndsAtTheLastSequenceWithoutWrapping() {
    BitsAllocator bits = new BitsAllocator(3, 2, 2);
    DefaultUniqueIdGenerator source = new DefaultUniqueIdGenerator(bits, 3);
    RingBuffer ring = new RingBuffer(4);
    assertEquals(4, source.fillBuffered(ring, 7, 4));
    for (int sequence = 0; sequence < 4; sequence++) {
      assertEquals(bits.allocate(7, 3, sequence), ring.poll());
    }
    assertEquals(
        IdException.Reason.CAPACITY,
        assertThrows(IdException.class, () -> source.fillBuffered(ring, 7, 1)).getReason());
    assertEquals(0, ring.poll());
    assertEquals(0, source.next(7));
  }

  @Test
  @Timeout(15)
  void concurrentConsumersSeeEveryBatchedPayloadExactlyOnceAcrossManyLaps() throws Exception {
    RingBuffer ring = new RingBuffer(8);
    int count = 20_000;
    AtomicIntegerArray seen = new AtomicIntegerArray(count + 1);
    AtomicInteger consumed = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(9);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      jobs.add(
          pool.submit(
              () -> {
                long next = 1;
                while (next <= count && !Thread.currentThread().isInterrupted()) {
                  int offered = ring.offerRange(next, (int) Math.min(7, count - next + 1));
                  next += offered;
                  if (offered == 0) {
                    Thread.yield();
                  }
                }
              }));
      for (int i = 0; i < 8; i++) {
        jobs.add(
            pool.submit(
                () -> {
                  while (consumed.get() < count && !Thread.currentThread().isInterrupted()) {
                    long id = ring.pollClaim();
                    if (id <= 0) {
                      assertTrue(id == 0 || id == RingBuffer.CONTENDED);
                      Thread.yield();
                      continue;
                    }
                    assertTrue(id <= count);
                    assertEquals(1, seen.incrementAndGet((int) id), "Duplicate ID " + id);
                    consumed.incrementAndGet();
                  }
                }));
      }
      for (Future<?> job : jobs) {
        job.get(10, TimeUnit.SECONDS);
      }
      assertEquals(count, consumed.get());
      for (int id = 1; id <= count; id++) {
        assertEquals(1, seen.get(id));
      }
      assertEquals(0, ring.poll());
      assertEquals(0, ring.size());
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
