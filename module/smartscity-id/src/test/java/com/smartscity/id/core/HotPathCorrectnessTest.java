package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdSnapshot;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Protects the time admission and observation contracts while removing redundant hot-path work.
 *
 * @author jack
 */
class HotPathCorrectnessTest {
  @Test
  void millisecondAdmissionMatchesWholeSecondEncodingAtSignedAndFormatBoundaries() {
    List<BitsAllocator> layouts = new ArrayList<>(ClockRollbackTest.layouts());
    layouts.add(new BitsAllocator(new IdLayout(Instant.ofEpochSecond(-2), 3, 4, 4)));
    layouts.add(
        new BitsAllocator(new IdLayout(Instant.ofEpochSecond(Long.MIN_VALUE / 1000), 54, 4, 4)));
    layouts.add(
        new BitsAllocator(new IdLayout(Instant.ofEpochSecond(Long.MAX_VALUE / 1000 - 7), 3, 4, 4)));
    for (BitsAllocator bits : layouts) {
      Instant epoch = bits.getLayout().getEpoch();
      long first = epoch.toEpochMilli();
      long lastSecond = epoch.plusSeconds(bits.getMaxTime()).toEpochMilli();
      List<Long> samples =
          new ArrayList<>(
              Arrays.asList(
                  Long.MIN_VALUE,
                  -1001L,
                  -1000L,
                  -999L,
                  -1L,
                  0L,
                  1L,
                  Long.MAX_VALUE,
                  first - 1,
                  first,
                  first + 999,
                  lastSecond - 1,
                  lastSecond));
      if (lastSecond <= Long.MAX_VALUE - 1000) {
        samples.add(lastSecond + 999);
        samples.add(lastSecond + 1000);
      }
      for (long wall : samples) {
        // Independent reference: the original whole-second admission rule, including floor for
        // negative UTC timestamps. No subtraction of millisecond endpoints is safe in general.
        long second = Math.floorDiv(wall, 1000) - epoch.getEpochSecond();
        boolean valid = second >= 0 && second <= bits.getMaxTime();
        ControlledTime time = new ControlledTime();
        time.set(wall, 0);
        CoreTest.Lease lease = new CoreTest.Lease();
        GeneratorOptions options = GeneratorOptions.builder().bits(bits).capacity(2).build();
        if (valid) {
          try (CachedUniqueIdGenerator generator =
              new CachedUniqueIdGenerator(options, lease, time)) {
            long id = generator.getUniqueId();
            assertEquals(bits.allocate(second, lease.workerId(), 0), id);
            assertEquals(epoch.plusSeconds(second), generator.parse(id).getTimestamp());
          }
        } else {
          assertEquals(
              IdException.Reason.CAPACITY,
              assertThrows(
                      IdException.class, () -> new CachedUniqueIdGenerator(options, lease, time))
                  .getReason());
        }
        assertEquals(1, lease.closes.get());
      }
    }
  }

  @Test
  void producerDetectsTimestampExhaustionAndRejectsRemainingCachedIds() throws Exception {
    BitsAllocator bits = new BitsAllocator(7, 4, 4);
    long initialWall =
        bits.getLayout().getEpoch().plusSeconds(bits.getMaxTime()).toEpochMilli() + 999;
    ControlledTime time = new ControlledTime();
    time.set(initialWall, 0);
    try (PausedProducerTime paused = new PausedProducerTime(time);
        CachedUniqueIdGenerator generator =
            new CachedUniqueIdGenerator(
                GeneratorOptions.builder().bits(bits).capacity(4).build(),
                new CoreTest.Lease(),
                paused)) {
      try {
        paused.awaitPaused();
        time.set(initialWall + 1, 1_000_000);
        // Cached admission has no synchronous clock read. The already encoded ID is valid even
        // though the producer has not yet observed the wall-time boundary.
        assertEquals(bits.allocate(bits.getMaxTime(), 7, 0), generator.getUniqueId());
        assertEquals(3, generator.snapshot().getBuffered());
        paused.close();
        ClockRollbackConcurrencyTest.await(
            () -> generator.snapshot().getState().equals("CAPACITY"));
        assertEquals(
            IdException.Reason.CAPACITY,
            assertThrows(IdException.class, generator::getUniqueId).getReason());
        assertEquals(3, generator.snapshot().getBuffered());
        assertEquals(1, generator.snapshot().getGenerated());
        time.set(initialWall, 0);
        assertEquals(
            IdException.Reason.CAPACITY,
            assertThrows(IdException.class, generator::getUniqueId).getReason());
        assertEquals(3, generator.snapshot().getBuffered());
        assertEquals(2, generator.snapshot().getFailures().get(IdException.Reason.CAPACITY));
      } finally {
        paused.close();
      }
    }
  }

  @Test
  void successfulCachedCallDoesNotSampleTheTimeSource() {
    Thread caller = Thread.currentThread();
    int[] reads = new int[2];
    TimeSource time =
        new TimeSource() {
          public long nanoTime() {
            if (Thread.currentThread() == caller) {
              reads[0]++;
            }
            return 0;
          }

          public long wallTimeMillis() {
            if (Thread.currentThread() == caller) {
              reads[1]++;
            }
            return ControlledTime.BASE;
          }
        };
    try (CachedUniqueIdGenerator generator =
        new CachedUniqueIdGenerator(
            GeneratorOptions.builder().capacity(8).build(), new CoreTest.Lease(), time)) {
      Arrays.fill(reads, 0);
      assertTrue(generator.getUniqueId() > 0);
      assertEquals(0, reads[0]);
      assertEquals(0, reads[1]);
    }
  }

  @Test
  void successfulCountsExcludeSuspendedAndClosedCallsWithoutLosingFailureMetrics() {
    CoreTest.Lease lease = new CoreTest.Lease();
    try (CachedUniqueIdGenerator generator =
        new CachedUniqueIdGenerator(
            GeneratorOptions.builder().capacity(8).build(), lease, new CoreTest.Clock())) {
      assertTrue(generator.getUniqueId() > 0);
      lease.state = WorkerLease.State.SUSPENDED;
      assertEquals(
          IdException.Reason.COORDINATION,
          assertThrows(IdException.class, generator::getUniqueId).getReason());
      lease.state = WorkerLease.State.ACTIVE;
      assertTrue(generator.getUniqueId() > 0);
      generator.close();
      assertEquals(
          IdException.Reason.CLOSED,
          assertThrows(IdException.class, generator::getUniqueId).getReason());
      IdSnapshot snapshot = generator.snapshot();
      assertEquals(2, snapshot.getGenerated());
      assertEquals(1, snapshot.getFailures().get(IdException.Reason.COORDINATION));
      assertEquals(1, snapshot.getFailures().get(IdException.Reason.CLOSED));
      assertTrue(snapshot.getLatencyNanos() > 0);
      assertTrue(snapshot.getLatencyP99Nanos() >= snapshot.getLatencyP50Nanos());
    }
  }
}
