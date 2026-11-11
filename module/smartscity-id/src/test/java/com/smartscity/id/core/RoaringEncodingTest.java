package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdParts;
import com.smartscity.id.api.IdSnapshot;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Representation, ownership and concurrent allocation regressions.
 *
 * @author jack
 */
class RoaringEncodingTest {
  @Test
  void rejectsKnownUnsupportedLayoutAndNullDependencies() {
    try (BufferedIdGenerator g =
        new BufferedIdGenerator(
            GeneratorOptions.builder().bits(BitsAllocator.highThroughput()).build(),
            lease(new AtomicInteger()),
            TimeSource.SYSTEM)) {
      assertThrows(
          IllegalArgumentException.class, () -> new RoaringIdGenerator(g, new RoaringIdEncoder()));
      assertTrue(g.getUniqueId() > 0);
    }
    assertThrows(
        NullPointerException.class, () -> new RoaringIdGenerator(null, new RoaringIdEncoder()));
  }

  @Test
  void versionVectorsAndEveryPayloadBitArePreserved() {
    RoaringIdEncoder e = RoaringIdEncoder.forVersion("roaring-30-18-15-v1");
    assertEquals(1, e.encode(1));
    assertEquals(65536, e.encode(2));
    assertEquals(2, e.encode(1L << 33));
    assertEquals(1L << 45, e.encode(1L << 15));
    assertEquals(Long.MAX_VALUE, e.encode(Long.MAX_VALUE));
    long seen = 0;
    for (int b = 0; b < 63; b++) {
      long id = 1L << b, k = e.encode(id);
      assertEquals(1, Long.bitCount(k));
      assertEquals(0, seen & k);
      seen |= k;
      assertEquals(id, e.decode(k));
    }
    assertEquals(Long.MAX_VALUE, seen);
    for (String v : new String[] {null, "standard-v1", "roaring-30-18-15-v2"}) {
      assertThrows(IllegalArgumentException.class, () -> RoaringIdEncoder.forVersion(v));
    }
    for (long bad : new long[] {0, -1, Long.MIN_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> e.encode(bad));
      assertThrows(IllegalArgumentException.class, () -> e.decode(bad));
      assertThrows(IllegalArgumentException.class, () -> new IdentityIdEncoder().encode(bad));
    }
  }

  @Test
  void boundariesAndRandomDomainHaveNoCollisions() {
    RoaringIdEncoder e = new RoaringIdEncoder();
    Set<Long> original = new HashSet<>(), encoded = new HashSet<>();
    Random r = new Random(711);
    for (long t : new long[] {0, 1, 32767, 32768, (1L << 30) - 1}) {
      for (long w : new long[] {0, 1, 131071, 131072, 262143}) {
        for (long s : new long[] {0, 1, 2, 32767}) {
          long id = t << 33 | w << 15 | s;
          if (id > 0) {
            assertEquals(id, e.decode(e.encode(id)));
            original.add(id);
            encoded.add(e.encode(id));
          }
        }
      }
    }
    for (int i = 0; i < 100000; i++) {
      long id = r.nextLong() & Long.MAX_VALUE;
      if (id == 0) {
        continue;
      }
      assertEquals(id, e.decode(e.encode(id)));
      assertEquals(id, new IdentityIdEncoder().decode(id));
      original.add(id);
      encoded.add(e.encode(id));
    }
    assertEquals(original.size(), encoded.size());
  }

  @Test
  void decoratorDoesNotAllocateOrCloseDelegateAndParsesOriginal() {
    AtomicInteger calls = new AtomicInteger(), closes = new AtomicInteger();
    BitsAllocator bits = BitsAllocator.standard();
    long original = bits.allocate(100, 7, 12);
    IdGenerator d =
        new IdGenerator() {
          public long getUniqueId() {
            calls.incrementAndGet();
            return original;
          }

          public IdParts parse(long id) {
            assertEquals(original, id);
            return bits.parse(id);
          }

          public IdSnapshot snapshot() {
            return IdSnapshot.builder().generated(calls.get()).build();
          }

          public void close() {
            closes.incrementAndGet();
          }
        };
    RoaringIdGenerator view = new RoaringIdGenerator(d, new RoaringIdEncoder());
    assertEquals(0, calls.get());
    long encoded = view.getUniqueId();
    assertEquals(1, calls.get());
    assertEquals(7, view.parse(encoded).getWorkerId());
    assertEquals(1, view.snapshot().getGenerated());
    assertThrows(
        IllegalArgumentException.class, () -> new RoaringIdGenerator(view, new RoaringIdEncoder()));
    view.close();
    view.close();
    assertEquals(0, closes.get());
    assertEquals(
        IdException.Reason.CLOSED, assertThrows(IdException.class, view::getUniqueId).getReason());
    assertEquals(original, d.getUniqueId());
  }

  @Test
  void concurrentGeneratorsPreserveUniquenessAndSingleLease() throws Exception {
    for (boolean buffered : new boolean[] {false, true}) {
      AtomicInteger closes = new AtomicInteger();
      WorkerLease lease = lease(closes);
      // Exercise concurrent uniqueness; controlled-clock tests own deadline assertions.
      GeneratorOptions options =
          GeneratorOptions.builder().waitTimeout(java.time.Duration.ofSeconds(1)).build();
      IdGenerator d =
          buffered
              ? new BufferedIdGenerator(options, lease, TimeSource.SYSTEM)
              : new SequenceIdGenerator(options, lease, TimeSource.SYSTEM);
      try (d;
          RoaringIdGenerator view = new RoaringIdGenerator(d, new RoaringIdEncoder())) {
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
          List<Future<?>> f = new ArrayList<>();
          for (int t = 0; t < 4; t++) {
            f.add(
                pool.submit(
                    () -> {
                      for (int i = 0; i < 1000; i++) {
                        long id = view.getUniqueId();
                        assertTrue(ids.add(id));
                        assertEquals(7, view.parse(id).getWorkerId());
                      }
                    }));
          }
          for (Future<?> v : f) {
            v.get(10, TimeUnit.SECONDS);
          }
        } finally {
          pool.shutdownNow();
          assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(4000, ids.size());
        assertEquals(4000, d.snapshot().getGenerated());
        assertEquals(0, closes.get());
      }
      assertEquals(1, closes.get());
      d.close();
      assertEquals(1, closes.get());
    }
  }

  @Test
  void sequenceFailureAndLegacyCursorContracts() {
    AtomicInteger closes = new AtomicInteger();
    AtomicLong wall = new AtomicLong(1800000000000L), nano = new AtomicLong();
    TimeSource time =
        new TimeSource() {
          public long wallTimeMillis() {
            return wall.get();
          }

          public long nanoTime() {
            return nano.get();
          }
        };
    try (SequenceIdGenerator g =
        new SequenceIdGenerator(GeneratorOptions.builder().build(), lease(closes), time)) {
      long first = g.getUniqueId();
      assertEquals(0, g.parse(first).getSequence());
      wall.addAndGet(-2000);
      assertEquals(
          IdException.Reason.CLOCK, assertThrows(IdException.class, g::getUniqueId).getReason());
      wall.addAndGet(2000);
      assertThrows(IdException.class, g::getUniqueId);
    }
    assertEquals(1, closes.get());
    DefaultUniqueIdGenerator c = new DefaultUniqueIdGenerator(new BitsAllocator(30, 18, 1), 7);
    assertTrue(c.next(1) > 0);
    assertTrue(c.next(1) > 0);
    assertEquals(0, c.next(1));
  }

  static WorkerLease lease(AtomicInteger closes) {
    return new WorkerLease() {
      public long rawId() {
        return 7;
      }

      public long workerId() {
        return 7;
      }

      public State state() {
        return State.ACTIVE;
      }

      public void close() {
        closes.incrementAndGet();
      }
    };
  }
}
