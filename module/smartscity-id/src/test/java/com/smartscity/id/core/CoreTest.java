package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdParts;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies encoding bounds, cache behavior and concurrent uniqueness within one allocation.
 *
 * @author jack
 */
class CoreTest {
  @Test
  void unsafeModuloAllocatorDemonstratesWhyCommittedWorkersCannotBeReused() {
    for (BitsAllocator bits :
        Arrays.asList(
            BitsAllocator.standard(),
            BitsAllocator.highThroughput(),
            BitsAllocator.javascript(15))) {
      // Deliberately bypass the registry to preserve the legacy collision counterexample.
      long originalRaw = 7;
      long wrappedRaw = originalRaw + bits.getWorkerSlots();

      DefaultUniqueIdGenerator oldInstance =
          new DefaultUniqueIdGenerator(bits, originalRaw % bits.getWorkerSlots());
      DefaultUniqueIdGenerator newInstance =
          new DefaultUniqueIdGenerator(bits, wrappedRaw % bits.getWorkerSlots());
      long prior = oldInstance.next(100);
      // Different raw IDs or successive ZK owners do not add incarnation bits to the encoded ID.
      assertEquals(prior, newInstance.next(100));
      assertNotEquals(prior, newInstance.next(101));
      // An old instance surviving the entire Worker cycle can overlap again in a later second.
      assertEquals(oldInstance.next(102), newInstance.next(102));
    }
  }

  static final class Clock implements TimeSource {
    final AtomicLong nanos = new AtomicLong();
    volatile long offset;

    public long nanoTime() {
      return nanos.get();
    }

    public long wallTimeMillis() {
      return BitsAllocator.EPOCH.toEpochMilli() + 100_000 + nanos.get() / 1_000_000 + offset;
    }
  }

  static final class Lease implements WorkerLease {
    volatile State state = State.ACTIVE;
    final AtomicInteger closes = new AtomicInteger();

    public long rawId() {
      return 7;
    }

    public long workerId() {
      return 7;
    }

    public State state() {
      return state;
    }

    public void close() {
      state = State.CLOSED;
      closes.incrementAndGet();
    }
  }

  @Test
  void formatsRoundTripAtLimitsAndJavascriptRemainsSafe() {
    for (BitsAllocator bits :
        Arrays.asList(
            BitsAllocator.standard(),
            BitsAllocator.highThroughput(),
            BitsAllocator.javascript(15),
            BitsAllocator.javascript(16),
            BitsAllocator.javascript(17))) {
      long id = bits.allocate(bits.getMaxTime(), bits.getWorkerSlots() - 1, bits.getMaxSequence());
      IdParts parsed = bits.parse(id);
      assertEquals(
          bits.getMaxTime(),
          parsed.getTimestamp().getEpochSecond() - BitsAllocator.EPOCH.getEpochSecond());
      assertEquals(bits.getWorkerSlots() - 1, parsed.getWorkerId());
      assertEquals(bits.getMaxSequence(), parsed.getSequence());
      if (bits.getWorkerBits() == 10) {
        assertEquals((1L << 53) - 1, id);
      }
      assertThrows(IdException.class, () -> bits.allocate(bits.getMaxTime() + 1, 0, 0));
      assertThrows(IdException.class, () -> bits.allocate(1, bits.getWorkerSlots(), 0));
      assertThrows(IdException.class, () -> bits.allocate(1, 0, bits.getMaxSequence() + 1));
    }
    assertThrows(IdException.class, () -> BitsAllocator.javascript(14));
    assertThrows(IdException.class, () -> BitsAllocator.javascript(15).parse(1L << 53));
    assertThrows(IdException.class, () -> BitsAllocator.standard().parse(0));
  }

  @Test
  void exhaustedSequenceNeverBorrowsTimeAndZeroIsSkipped() {
    BitsAllocator bits = new BitsAllocator(10, 3, 2);
    DefaultUniqueIdGenerator source = new DefaultUniqueIdGenerator(bits, 0);
    assertEquals(1, source.next(0));
    assertEquals(2, source.next(0));
    assertEquals(3, source.next(0));
    assertEquals(0, source.next(0));
    assertEquals(bits.allocate(1, 0, 0), source.next(1));
    assertThrows(IdException.class, () -> source.next(0));
    assertThrows(IdException.class, () -> source.next(1024));
  }

  @Test
  void clockIgnoresSmallWallRollbackButRejectsLargeDeviation() {
    Clock source = new Clock();
    MonotonicClock clock = new MonotonicClock(source, 1000);
    long before = clock.seconds();
    source.nanos.addAndGet(2_000_000_000L);
    source.offset = -500;
    assertEquals(before + 2, clock.seconds());
    source.offset = -1001;
    assertEquals(
        IdException.Reason.CLOCK, assertThrows(IdException.class, clock::seconds).getReason());
  }

  @Test
  @Timeout(15)
  void ringConsumersClaimEveryItemExactlyOnceAcrossManyLaps() throws Exception {
    RingBuffer ring = new RingBuffer(8);
    int count = 20000;
    AtomicIntegerArray seen = new AtomicIntegerArray(count + 1);
    AtomicInteger consumed = new AtomicInteger();
    ExecutorService threads = Executors.newFixedThreadPool(9);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      jobs.add(
          threads.submit(
              () -> {
                for (long id = 1; id <= count; id++) {
                  while (!ring.offer(id)) {
                    Thread.yield();
                  }
                }
              }));
      for (int i = 0; i < 8; i++) {
        jobs.add(
            threads.submit(
                () -> {
                  while (consumed.get() < count) {
                    long id = ring.poll();
                    if (id == 0) {
                      Thread.yield();
                      continue;
                    }
                    assertEquals(1, seen.incrementAndGet((int) id), "duplicate claim " + id);
                    consumed.incrementAndGet();
                  }
                }));
      }
      for (Future<?> job : jobs) {
        job.get(10, TimeUnit.SECONDS);
      }
      for (int i = 1; i <= count; i++) {
        assertEquals(1, seen.get(i));
      }
      assertEquals(0, ring.poll());
    } finally {
      threads.shutdownNow();
    }
  }

  @Test
  void defaultParametersAndInvalidCombinations() {
    GeneratorOptions defaults = GeneratorOptions.builder().build();
    assertEquals(16384, defaults.capacity);
    assertEquals(50, defaults.lowWaterPercent);
    assertEquals(10_000_000L, defaults.waitNanos);
    assertThrows(
        IllegalArgumentException.class, () -> GeneratorOptions.builder().capacity(3).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> GeneratorOptions.builder().waitTimeout(Duration.ofSeconds(2)).build());
  }

  @Test
  @Timeout(5)
  void cacheBorrowsFutureSecondsWithoutReusingIdsAtAFrozenWallClock() throws Exception {
    Clock clock = new Clock();
    Lease lease = new Lease();
    GeneratorOptions options =
        GeneratorOptions.builder()
            .bits(new BitsAllocator(20, 4, 2))
            .capacity(4)
            .waitTimeout(Duration.ofSeconds(1))
            .build();
    try (CachedUniqueIdGenerator generator = new CachedUniqueIdGenerator(options, lease, clock)) {
      Set<Long> ids = new HashSet<>();
      long previous = 0;
      for (int i = 0; i < 1024; i++) {
        long id = generator.getUniqueId();
        assertTrue(ids.add(id));
        assertTrue(id > previous);
        previous = id;
      }
      assertTrue(
          generator.parse(previous).getTimestamp().getEpochSecond()
              > BitsAllocator.EPOCH.getEpochSecond() + 100);
      assertTrue(generator.snapshot().isReady());
      assertEquals(1024, generator.snapshot().getGenerated());
    }
    assertEquals(1, lease.closes.get());
  }

  @Test
  void bufferedCursorAdvancesLogicalTimeSkipsReservedZeroAndNeverWraps() {
    BitsAllocator bits = new BitsAllocator(3, 1, 2);
    DefaultUniqueIdGenerator source = new DefaultUniqueIdGenerator(bits, 0);
    Set<Long> ids = new HashSet<>();
    for (long expected = 1; expected <= 3; expected++) {
      assertEquals(expected, source.nextBuffered(0));
      ids.add(expected);
    }
    long future = source.nextBuffered(0);
    assertEquals(bits.allocate(1, 0, 0), future);
    assertTrue(ids.add(future));
    assertEquals(bits.allocate(5, 0, 0), source.nextBuffered(5));
    assertEquals(bits.allocate(5, 0, 1), source.nextBuffered(0));
    for (int sequence = 0; sequence < 4; sequence++) {
      assertEquals(bits.allocate(7, 0, sequence), source.nextBuffered(7));
    }
    assertEquals(
        IdException.Reason.CAPACITY,
        assertThrows(IdException.class, () -> source.nextBuffered(0)).getReason());
    assertEquals(
        IdException.Reason.CAPACITY,
        assertThrows(IdException.class, () -> source.nextBuffered(8)).getReason());
  }

  @Test
  void cachedLogicalExhaustionIsTerminalAndNeverWrapsTimestamp() {
    BitsAllocator bits = new BitsAllocator(7, 4, 1);
    Set<Long> ids = new HashSet<>();
    try (CachedUniqueIdGenerator generator =
        new CachedUniqueIdGenerator(
            GeneratorOptions.builder().bits(bits).capacity(4).build(), new Lease(), new Clock())) {
      boolean exhausted = false;
      for (int i = 0; i < 100; i++) {
        try {
          long id = generator.getUniqueId();
          assertTrue(ids.add(id));
          long second =
              generator.parse(id).getTimestamp().getEpochSecond()
                  - BitsAllocator.EPOCH.getEpochSecond();
          assertTrue(second >= 100 && second <= 127);
        } catch (IdException e) {
          assertEquals(IdException.Reason.CAPACITY, e.getReason());
          exhausted = true;
          break;
        }
      }
      assertTrue(exhausted);
      assertTrue(ids.size() <= 56);
      assertEquals(
          IdException.Reason.CAPACITY,
          assertThrows(IdException.class, generator::getUniqueId).getReason());
      assertFalse(generator.snapshot().isReady());
    }
  }

  @Test
  @Timeout(5)
  void cachedIdsAreRejectedAfterClockFailureAndDoNotAutoResume() {
    Clock clock = new Clock();
    Lease lease = new Lease();
    try (CachedUniqueIdGenerator g =
        new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(8).build(), lease, clock)) {
      clock.offset = 2000;
      assertEquals(
          IdException.Reason.CLOCK, assertThrows(IdException.class, g::getUniqueId).getReason());
      clock.offset = 0;
      assertThrows(IdException.class, g::getUniqueId);
      assertFalse(g.snapshot().isReady());
    }
  }

  @Test
  void coordinationSuspendResumeExpireAndClose() {
    Clock clock = new Clock();
    Lease lease = new Lease();
    CachedUniqueIdGenerator g =
        new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(8).build(), lease, clock);
    long first = g.getUniqueId();
    lease.state = WorkerLease.State.SUSPENDED;
    assertEquals(
        IdException.Reason.COORDINATION,
        assertThrows(IdException.class, g::getUniqueId).getReason());
    lease.state = WorkerLease.State.ACTIVE;
    assertNotEquals(first, g.getUniqueId());
    lease.state = WorkerLease.State.EXPIRED;
    assertThrows(IdException.class, g::getUniqueId);
    lease.state = WorkerLease.State.ACTIVE;
    assertThrows(IdException.class, g::getUniqueId);
    g.close();
    g.close();
    assertEquals(1, lease.closes.get());
    assertEquals(
        IdException.Reason.CLOSED, assertThrows(IdException.class, g::getUniqueId).getReason());
  }

  @Test
  void interruptedCallPreservesInterruptFlag() {
    try (CachedUniqueIdGenerator g =
        new CachedUniqueIdGenerator(
            GeneratorOptions.builder().capacity(8).build(), new Lease(), new Clock())) {
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            IdException.Reason.INTERRUPTED,
            assertThrows(IdException.class, g::getUniqueId).getReason());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  @Timeout(10)
  void cachedGeneratorConcurrentCallsAreUnique() throws Exception {
    ExecutorService threads = Executors.newFixedThreadPool(8);
    Set<Long> ids = ConcurrentHashMap.newKeySet();
    try (CachedUniqueIdGenerator g =
        new CachedUniqueIdGenerator(
            GeneratorOptions.builder()
                .bits(new BitsAllocator(20, 4, 4))
                .capacity(64)
                .waitTimeout(Duration.ofSeconds(1))
                .build(),
            new Lease(),
            new Clock())) {
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        jobs.add(
            threads.submit(
                () -> {
                  for (int n = 0; n < 500; n++) {
                    assertTrue(ids.add(g.getUniqueId()));
                  }
                }));
      }
      for (Future<?> job : jobs) {
        job.get(5, TimeUnit.SECONDS);
      }
      assertEquals(4000, ids.size());
    } finally {
      threads.shutdownNow();
    }
  }
}
