package com.smartscity.id.compatibility;

import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * No JUnit/Spring dependency at runtime; execute the same Java 9 class files on each JDK.
 *
 * @author jack
 */
public final class CoreRuntimeProbe {
  public static void main(String[] args) {
    int total = 0;
    for (BitsAllocator bits :
        Arrays.asList(
            BitsAllocator.standard(),
            BitsAllocator.highThroughput(),
            BitsAllocator.javascript(15),
            BitsAllocator.javascript(16),
            BitsAllocator.javascript(17))) {
      WorkerLease lease =
          new WorkerLease() {
            public long rawId() {
              return 123;
            }

            public long workerId() {
              return 123;
            }

            public State state() {
              return State.ACTIVE;
            }

            public void close() {}
          };
      try (IdGenerator g =
          new CachedUniqueIdGenerator(
              GeneratorOptions.builder().bits(bits).capacity(64).build(),
              lease,
              TimeSource.SYSTEM)) {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
          long id = g.getUniqueId();
          if (!seen.add(id) || g.parse(id).getWorkerId() != 123) {
            throw new AssertionError("ID runtime probe failed");
          }
          if (bits.getWorkerBits() == 10 && id > (1L << 53) - 1) {
            throw new AssertionError("Unsafe JavaScript ID");
          }
          total++;
        }
      }
    }
    System.out.println(
        "PASS java=" + System.getProperty("java.version") + " ids=" + total + " layouts=5");
  }
}
