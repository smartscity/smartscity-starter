package com.smartscity.id.benchmark;

import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;

/**
 * Benchmarks local generation only. Database/ZK lifecycle is exercised by integration tests.
 *
 * @author jack
 */
final class BenchmarkGenerator {
  private BenchmarkGenerator() {}

  static CachedUniqueIdGenerator create(String profile) {
    BitsAllocator bits;
    switch (profile) {
      case "standard":
        bits = BitsAllocator.standard();
        break;
      case "javascript":
        bits = BitsAllocator.javascript(15);
        break;
      case "high-throughput":
        bits = BitsAllocator.highThroughput();
        break;
      default:
        throw new IllegalArgumentException(profile);
    }
    return new CachedUniqueIdGenerator(
        GeneratorOptions.builder().bits(bits).build(),
        new WorkerLease() {
          private volatile State state = State.ACTIVE;

          public long rawId() {
            return 1;
          }

          public long workerId() {
            return 1;
          }

          public State state() {
            return state;
          }

          public void close() {
            state = State.CLOSED;
          }
        },
        TimeSource.SYSTEM);
  }
}
