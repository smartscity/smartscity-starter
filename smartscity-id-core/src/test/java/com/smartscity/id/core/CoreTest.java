package com.smartscity.id.core;

import com.smartscity.id.api.*;
import com.smartscity.id.spi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @Test void moduloWrapAndSameSecondReuseAreExplicitUniquenessLimits() {
        for (BitsAllocator bits : Arrays.asList(BitsAllocator.standard(), BitsAllocator.highThroughput(), BitsAllocator.javascript(15))) {
            long originalRaw = 7, wrappedRaw = originalRaw + bits.getWorkerSlots();
            DefaultUniqueIdGenerator oldInstance = new DefaultUniqueIdGenerator(bits, originalRaw % bits.getWorkerSlots());
            DefaultUniqueIdGenerator newInstance = new DefaultUniqueIdGenerator(bits, wrappedRaw % bits.getWorkerSlots());
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
        public long nanoTime() { return nanos.get(); }
        public long wallTimeMillis() { return BitsAllocator.EPOCH.toEpochMilli() + 100_000 + nanos.get() / 1_000_000 + offset; }
    }
    static final class Lease implements WorkerLease {
        volatile State state = State.ACTIVE;
        final AtomicInteger closes = new AtomicInteger();
        public long rawId() { return 7; }
        public long workerId() { return 7; }
        public State state() { return state; }
        public void close() { state = State.CLOSED; closes.incrementAndGet(); }
    }
    @Test void formatsRoundTripAtLimitsAndJavascriptRemainsSafe() {
        for (BitsAllocator bits : Arrays.asList(BitsAllocator.standard(), BitsAllocator.highThroughput(),
                BitsAllocator.javascript(15), BitsAllocator.javascript(16), BitsAllocator.javascript(17))) {
            long id = bits.allocate(bits.getMaxTime(), bits.getWorkerSlots() - 1, bits.getMaxSequence());
            IdParts parsed = bits.parse(id);
            assertEquals(bits.getMaxTime(), parsed.getTimestamp().getEpochSecond() - BitsAllocator.EPOCH.getEpochSecond());
            assertEquals(bits.getWorkerSlots() - 1, parsed.getWorkerId());
            assertEquals(bits.getMaxSequence(), parsed.getSequence());
            if (bits.getWorkerBits() == 10) assertEquals((1L << 53) - 1, id);
            assertThrows(IdException.class, () -> bits.allocate(bits.getMaxTime() + 1, 0, 0));
            assertThrows(IdException.class, () -> bits.allocate(1, bits.getWorkerSlots(), 0));
            assertThrows(IdException.class, () -> bits.allocate(1, 0, bits.getMaxSequence() + 1));
        }
        assertThrows(IdException.class, () -> BitsAllocator.javascript(14));
        assertThrows(IdException.class, () -> BitsAllocator.javascript(15).parse(1L << 53));
        assertThrows(IdException.class, () -> BitsAllocator.standard().parse(0));
    }
    @Test void exhaustedSequenceNeverBorrowsTimeAndZeroIsSkipped() {
        BitsAllocator bits = new BitsAllocator(10, 3, 2);
        DefaultUniqueIdGenerator source = new DefaultUniqueIdGenerator(bits, 0);
        assertEquals(1, source.next(0)); assertEquals(2, source.next(0)); assertEquals(3, source.next(0));
        assertEquals(0, source.next(0));
        assertEquals(bits.allocate(1, 0, 0), source.next(1));
        assertThrows(IdException.class, () -> source.next(0));
        assertThrows(IdException.class, () -> source.next(1024));
    }
    @Test void clockIgnoresSmallWallRollbackButRejectsLargeDeviation() {
        Clock source = new Clock(); MonotonicClock clock = new MonotonicClock(source, 1000);
        long before = clock.seconds(); source.nanos.addAndGet(2_000_000_000L); source.offset = -500;
        assertEquals(before + 2, clock.seconds()); source.offset = -1001;
        assertEquals(IdException.Reason.CLOCK, assertThrows(IdException.class, clock::seconds).getReason());
    }
    @Test @Timeout(15) void ringConsumersClaimEveryItemExactlyOnceAcrossManyLaps() throws Exception {
        RingBuffer ring = new RingBuffer(8);
        int count = 20000;
        AtomicIntegerArray seen = new AtomicIntegerArray(count + 1);
        AtomicInteger consumed = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(9);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            jobs.add(threads.submit(() -> {
                for (long id = 1; id <= count; id++) while (!ring.offer(id)) Thread.yield();
            }));
            for (int i = 0; i < 8; i++) jobs.add(threads.submit(() -> {
                while (consumed.get() < count) {
                    long id = ring.poll();
                    if (id == 0) { Thread.yield(); continue; }
                    assertEquals(1, seen.incrementAndGet((int) id), "duplicate claim " + id);
                    consumed.incrementAndGet();
                }
            }));
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
            for (int i = 1; i <= count; i++) assertEquals(1, seen.get(i));
            assertEquals(0, ring.poll());
        } finally { threads.shutdownNow(); }
    }
    @Test void defaultParametersAndInvalidCombinations() {
        GeneratorOptions defaults = GeneratorOptions.builder().build();
        assertEquals(16384, defaults.capacity); assertEquals(50, defaults.lowWaterPercent);
        assertEquals(10_000_000L, defaults.waitNanos);
        assertThrows(IllegalArgumentException.class, () -> GeneratorOptions.builder().capacity(3).build());
        assertThrows(IllegalArgumentException.class, () -> GeneratorOptions.builder().waitTimeout(Duration.ofSeconds(2)).build());
    }
    @Test @Timeout(5) void cacheTimeoutThenNextSecondRecoversWithoutReusingIds() throws Exception {
        Clock clock = new Clock(); Lease lease = new Lease();
        GeneratorOptions o = GeneratorOptions.builder().bits(new BitsAllocator(20, 4, 2)).capacity(4).build();
        try (CachedUniqueIdGenerator generator = new CachedUniqueIdGenerator(o, lease, clock)) {
            Set<Long> ids = new HashSet<>();
            for (int i = 0; i < 4; i++) assertTrue(ids.add(generator.getUniqueId()));
            assertEquals(IdException.Reason.TIMEOUT, assertThrows(IdException.class, generator::getUniqueId).getReason());
            clock.nanos.addAndGet(1_000_000_000L);
            assertTrue(ids.add(generator.getUniqueId()));
            assertTrue(generator.snapshot().getWaits() > 0);
        }
        assertEquals(1, lease.closes.get());
    }
    @Test @Timeout(5) void cachedIdsAreRejectedAfterClockFailureAndDoNotAutoResume() {
        Clock clock = new Clock(); Lease lease = new Lease();
        try (CachedUniqueIdGenerator g = new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(8).build(), lease, clock)) {
            clock.offset = 2000;
            assertEquals(IdException.Reason.CLOCK, assertThrows(IdException.class, g::getUniqueId).getReason());
            clock.offset = 0;
            assertThrows(IdException.class, g::getUniqueId); assertFalse(g.snapshot().isReady());
        }
    }
    @Test void coordinationSuspendResumeExpireAndClose() {
        Clock clock = new Clock(); Lease lease = new Lease();
        CachedUniqueIdGenerator g = new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(8).build(), lease, clock);
        long first = g.getUniqueId(); lease.state = WorkerLease.State.SUSPENDED;
        assertEquals(IdException.Reason.COORDINATION, assertThrows(IdException.class, g::getUniqueId).getReason());
        lease.state = WorkerLease.State.ACTIVE; assertNotEquals(first, g.getUniqueId());
        lease.state = WorkerLease.State.EXPIRED; assertThrows(IdException.class, g::getUniqueId);
        lease.state = WorkerLease.State.ACTIVE; assertThrows(IdException.class, g::getUniqueId);
        g.close(); g.close(); assertEquals(1, lease.closes.get());
        assertEquals(IdException.Reason.CLOSED, assertThrows(IdException.class, g::getUniqueId).getReason());
    }
    @Test void interruptedCallPreservesInterruptFlag() {
        try (CachedUniqueIdGenerator g = new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(8).build(), new Lease(), new Clock())) {
            Thread.currentThread().interrupt();
            try {
                assertEquals(IdException.Reason.INTERRUPTED, assertThrows(IdException.class, g::getUniqueId).getReason());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }
    @Test @Timeout(10) void cachedGeneratorConcurrentCallsAreUnique() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(8);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (CachedUniqueIdGenerator g = new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(64).waitTimeout(Duration.ofSeconds(1)).build(), new Lease(), new Clock())) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 8; i++) jobs.add(threads.submit(() -> {
                for (int n = 0; n < 500; n++) assertTrue(ids.add(g.getUniqueId()));
            }));
            for (Future<?> job : jobs) job.get(5, TimeUnit.SECONDS);
            assertEquals(4000, ids.size());
        } finally { threads.shutdownNow(); }
    }
}
