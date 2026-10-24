package com.smartscity.id.core;

import com.smartscity.id.api.*;
import com.smartscity.id.spi.TimeSource;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic clock faults; never changes the host clock. */
class ClockRollbackTest {
    private static final long BASE = ControlledTime.BASE;
    static List<BitsAllocator> layouts() {
        return Arrays.asList(BitsAllocator.standard(), BitsAllocator.highThroughput(),
                BitsAllocator.javascript(15), BitsAllocator.javascript(16), BitsAllocator.javascript(17));
    }
    static void clockFailure(org.junit.jupiter.api.function.Executable call) {
        assertEquals(IdException.Reason.CLOCK, assertThrows(IdException.class, call).getReason());
    }
    @Test void negativeNanoOriginAndSignedCounterWrapPreserveElapsedTime() {
        for (long origin : new long[] {-2_000_000, Long.MAX_VALUE - 500_000}) {
            ControlledTime time = new ControlledTime(); time.set(BASE, origin);
            MonotonicClock clock = new MonotonicClock(time, 1000);
            time.set(BASE + 1, origin + 1_000_000); // Signed wrap is legal for nanoTime differences.
            assertEquals(BASE + 1, clock.millis());
        }
    }
    @Test void samplingUncertaintyIsAccountedForAtBothToleranceEdges() {
        for (long wall : new long[] {BASE - 990, BASE + 1011, BASE - 991, BASE + 1012}) {
            Samples time = new Samples().sample(0, BASE, 0).sample(10_000_000, wall, 11_000_000);
            MonotonicClock clock = new MonotonicClock(time, 1000);
            if (wall == BASE - 990 || wall == BASE + 1011) assertEquals(BASE + 11, clock.millis());
            else clockFailure(clock::millis);
        }
    }
    @Test void repeatedUnstableStartupSamplesCannotEstablishAnAnchor() {
        Samples time = new Samples();
        for (int i = 0; i < 3; i++) time.sample(i * 2_000_000L, BASE + i * 2, i * 2_000_000L + 1_000_001);
        clockFailure(() -> new MonotonicClock(time, 1000));
        assertEquals(3, time.consumed);
    }
    @Test void runtimeSampleRetriesHaveABoundAndNeverReanchor() {
        Samples time = new Samples().sample(0, BASE, 0);
        for (int i = 0; i < 3; i++) time.sample(i * 2_000_000L, BASE + i * 2, i * 2_000_000L + 1_000_001);
        time.sample(2_000_000_000L, BASE + 2000, 2_000_000_000L);
        MonotonicClock clock = new MonotonicClock(time, 1000);
        clockFailure(clock::millis); assertEquals(4, time.consumed);
        assertEquals(BASE, clock.getAnchorMillis()); assertEquals(BASE + 2000, clock.millis());
        // MonotonicClock validates samples; the generator owns terminal failure retention.
    }
    @Test void pauseBeforeOrAfterWallReadRetriesAgainstTheOriginalAnchor() {
        for (long pausedWall : new long[] {BASE, BASE + 2000}) {
            Samples time = new Samples().sample(0, BASE, 0)
                    .sample(0, pausedWall, 2_000_000_000L)
                    .sample(2_000_000_000L, BASE + 2000, 2_000_000_000L);
            MonotonicClock clock = new MonotonicClock(time, 1000);
            assertEquals(BASE + 2000, clock.millis()); assertEquals(BASE, clock.getAnchorMillis());
        }
    }
    @Test void negativeSampleSpanAndElapsedBeforeAnchorAreRejected() {
        clockFailure(() -> new MonotonicClock(new Samples().sample(10, BASE, 9), 1000));
        for (Samples time : Arrays.asList(
                new Samples().sample(0, BASE, 0).sample(10, BASE, 9),
                new Samples().sample(10, BASE, 10).sample(9, BASE, 9))) {
            MonotonicClock clock = new MonotonicClock(time, 1000); clockFailure(clock::millis);
        }
    }
    @Test void logicalWallAdditionOverflowFailsClosed() {
        ControlledTime time = new ControlledTime(); time.set(Long.MAX_VALUE - 1, 0);
        MonotonicClock clock = new MonotonicClock(time, 1000);
        time.set(Long.MAX_VALUE, 2_000_000);
        clockFailure(clock::millis);
    }
    @Test void suspendModelsDistinguishCoherentAdvanceFromOneClockStopping() {
        ControlledTime time = new ControlledTime(); MonotonicClock clock = new MonotonicClock(time, 1000);
        time.set(BASE + 7_200_000, 7_200_000_000_000L);
        assertEquals(BASE + 7_200_000, clock.millis());
        for (boolean wallOnly : new boolean[] {true, false}) {
            ControlledTime stopped = new ControlledTime(); MonotonicClock validator = new MonotonicClock(stopped, 1000);
            stopped.set(BASE + (wallOnly ? 2000 : 0), wallOnly ? 0 : 2_000_000_000L);
            clockFailure(validator::millis);
        }
    }
    @Test void incrementalDriftUsesOriginalAnchorAndFailureRemainsTerminalForEveryLayout() {
        for (BitsAllocator bits : layouts()) for (int direction : new int[] {-1, 1}) {
            ControlledTime time = new ControlledTime(); CoreTest.Lease lease = new CoreTest.Lease(); Set<Long> ids = new HashSet<>();
            try (CachedUniqueIdGenerator generator = new CachedUniqueIdGenerator(
                    GeneratorOptions.builder().bits(bits).capacity(4).build(), lease, time)) {
                for (int step = 1; step <= 10; step++) {
                    time.set(BASE + step * 1000L + direction * step * 100L, step * 1_000_000_000L);
                    assertTrue(ids.add(generator.getUniqueId()));
                }
                time.set(BASE + 11000 + direction * 1100, 11_000_000_000L);
                clockFailure(generator::getUniqueId);
                time.set(BASE + 11000, 11_000_000_000L);
                for (int i = 0; i < 10; i++) clockFailure(generator::getUniqueId);
                assertFalse(generator.snapshot().isReady()); assertEquals("CLOCK", generator.snapshot().getState());
            }
            assertEquals(1, lease.closes.get());
        }
    }
    @Test void wallRollbackCannotReplenishAnExhaustedSecondOrExtendTheWait() {
        ControlledTime time = new ControlledTime(); Set<Long> ids = new HashSet<>();
        try (CachedUniqueIdGenerator generator = new CachedUniqueIdGenerator(GeneratorOptions.builder()
                .bits(new BitsAllocator(20, 4, 2)).capacity(4).waitTimeout(Duration.ofMillis(10)).build(), new CoreTest.Lease(), time)) {
            for (int i = 0; i < 4; i++) assertTrue(ids.add(generator.getUniqueId()));
            for (long offset : new long[] {-1000, 0, 1000}) {
                time.set(BASE + offset, 0);
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals(IdException.Reason.TIMEOUT,
                        assertThrows(IdException.class, generator::getUniqueId).getReason()));
            }
            time.set(BASE + 1000, 1_000_000_000);
            assertTrue(ids.add(generator.getUniqueId()));
        }
    }
    @Test void invalidEpochAndTimeFieldExhaustionRejectEvenCachedIdsForEveryLayout() {
        for (BitsAllocator bits : layouts()) {
            ControlledTime time = new ControlledTime(); time.set(BitsAllocator.EPOCH.toEpochMilli() - 1, 0);
            CoreTest.Lease badLease = new CoreTest.Lease();
            GeneratorOptions options = GeneratorOptions.builder().bits(bits).capacity(4).build();
            assertEquals(IdException.Reason.CAPACITY, assertThrows(IdException.class, () ->
                    new CachedUniqueIdGenerator(options, badLease, time)).getReason());
            assertEquals(1, badLease.closes.get());
            long last = BitsAllocator.EPOCH.toEpochMilli() + bits.getMaxTime() * 1000;
            time.set(last, 0);
            try (CachedUniqueIdGenerator generator = new CachedUniqueIdGenerator(options, new CoreTest.Lease(), time)) {
                assertTrue(generator.getUniqueId() > 0);
                assertTrue(generator.snapshot().getBuffered() > 0);
                time.set(last + 1000, 1_000_000_000);
                assertEquals(IdException.Reason.CAPACITY, assertThrows(IdException.class, generator::getUniqueId).getReason());
            }
        }
    }
    /** Explicit sample spans, used only by single-threaded validator tests. */
    private static final class Samples implements TimeSource {
        final List<long[]> samples = new ArrayList<>();
        int consumed; boolean started;
        Samples sample(long before, long wall, long after) { samples.add(new long[] {before, wall, after}); return this; }
        public long nanoTime() {
            long[] sample = samples.get(consumed);
            if (!started) { started = true; return sample[0]; }
            started = false; consumed++; return sample[2];
        }
        public long wallTimeMillis() { assertTrue(started); return samples.get(consumed)[1]; }
    }
}
