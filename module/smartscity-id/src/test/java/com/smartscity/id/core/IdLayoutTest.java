package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies immutable format validation and configured epoch use across encoding and caching.
 *
 * @author jack
 */
class IdLayoutTest {
  @Test
  void defaultsPreserveEncodingAndEpoch() {
    BitsAllocator bits = new BitsAllocator(IdLayout.standard());
    long id = bits.allocate(100, 7, 31);
    assertEquals((100L << 33) | (7L << 15) | 31, id);
    assertEquals(Instant.parse("2026-01-01T00:01:40Z"), bits.parse(id).getTimestamp());
    assertEquals(BitsAllocator.EPOCH, bits.getLayout().getEpoch());
    assertEquals("30/18/15", bits.signature());
  }

  @Test
  void customEpochRoundTripsAndChecksEveryField() {
    BitsAllocator bits =
        new BitsAllocator(new IdLayout(Instant.parse("2020-01-01T00:00:00Z"), 10, 3, 2));
    long id = bits.allocate(1023, 7, 3);
    assertEquals(Instant.parse("2020-01-01T00:17:03Z"), bits.parse(id).getTimestamp());
    assertEquals(7, bits.parse(id).getWorkerId());
    assertEquals(3, bits.parse(id).getSequence());
    assertThrows(IdException.class, () -> bits.allocate(1024, 7, 3));
    assertThrows(IdException.class, () -> bits.allocate(1, 8, 0));
    assertThrows(IdException.class, () -> bits.allocate(1, 0, 4));
    assertThrows(IllegalArgumentException.class, () -> new DefaultUniqueIdGenerator(bits, 8));
    DefaultUniqueIdGenerator source = new DefaultUniqueIdGenerator(bits, 1);
    for (int i = 0; i < 4; i++) {
      assertEquals(i, bits.parse(source.next(1)).getSequence());
    }
    assertEquals(0, source.next(1));
    assertThrows(IdException.class, () -> source.next(0));
    assertNotEquals(id, source.next(2));
  }

  @Test
  void invalidFormatsFailBeforeGeneration() {
    for (int[] widths :
        new int[][] {
          {0, 18, 15}, {31, 18, 15}, {1, -1, 2}, {1, 1, 62}, {Integer.MAX_VALUE, 1, 1}
        }) {
      assertThrows(
          IdException.class,
          () -> new IdLayout(IdLayout.DEFAULT_EPOCH, widths[0], widths[1], widths[2]));
    }
    assertThrows(IdException.class, () -> new IdLayout(null, 30, 18, 15));
    assertThrows(IdException.class, () -> new IdLayout(Instant.EPOCH.plusNanos(1), 30, 18, 15));
    assertThrows(IdException.class, () -> new IdLayout(Instant.MAX, 30, 18, 15));
    assertThrows(IdException.class, () -> new IdLayout(Instant.EPOCH, 61, 1, 1));
    assertThrows(
        IllegalArgumentException.class, () -> GeneratorOptions.builder().capacity(3).build());
  }

  @Test
  @Timeout(15)
  void customFormatIsUniqueAcrossThreadsAndDistinctWorkers() throws Exception {
    Instant epoch = Instant.parse("2020-01-01T00:00:00Z");
    BitsAllocator bits = new BitsAllocator(new IdLayout(epoch, 30, 8, 15));
    GeneratorOptions options = GeneratorOptions.builder().bits(bits).capacity(1024).build();
    TimeSource time =
        new TimeSource() {
          public long nanoTime() {
            return 0;
          }

          public long wallTimeMillis() {
            return epoch.toEpochMilli() + 100_000;
          }
        };
    Set<Long> seen = ConcurrentHashMap.newKeySet();
    ExecutorService threads = Executors.newFixedThreadPool(4);
    try (CachedUniqueIdGenerator first = new CachedUniqueIdGenerator(options, lease(1), time);
        CachedUniqueIdGenerator second = new CachedUniqueIdGenerator(options, lease(2), time)) {
      java.util.List<Future<?>> jobs = new java.util.ArrayList<>();
      for (int i = 0; i < 4; i++) {
        CachedUniqueIdGenerator generator = i % 2 == 0 ? first : second;
        jobs.add(
            threads.submit(
                () -> {
                  for (int j = 0; j < 1000; j++) {
                    long id = generator.getUniqueId();
                    assertTrue(seen.add(id));
                    assertEquals(epoch.plusSeconds(100), generator.parse(id).getTimestamp());
                  }
                }));
      }
      for (Future<?> job : jobs) {
        job.get();
      }
      assertEquals(4000, seen.size());
    } finally {
      threads.shutdownNow();
    }
    // Restart is safe here because allocation provides a different encoded worker.
    try (CachedUniqueIdGenerator restarted = new CachedUniqueIdGenerator(options, lease(3), time)) {
      assertTrue(seen.add(restarted.getUniqueId()));
    }
  }

  private static WorkerLease lease(long worker) {
    return new WorkerLease() {
      public long rawId() {
        return worker;
      }

      public long workerId() {
        return worker;
      }

      public State state() {
        return State.ACTIVE;
      }

      public void close() {}
    };
  }
}
