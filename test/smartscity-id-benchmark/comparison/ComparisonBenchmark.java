package com.smartscity.id.benchmark.comparison;

import com.baidu.fsg.uid.exception.UidGenerateException;
import com.baidu.fsg.uid.impl.CachedUidGenerator;
import com.baidu.fsg.uid.impl.DefaultUidGenerator;
import com.smartscity.id.api.IdException;
import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Control;

/**
 * Compares unchanged local generation paths with identical format and consumer instrumentation.
 *
 * @author jack
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-Duser.timezone=UTC"})
public class ComparisonBenchmark {
  /**
   * Owns one shared generator and its producer resources for each trial.
   *
   * @author jack
   */
  @State(Scope.Benchmark)
  public static class Generator implements AutoCloseable {
    @Param({"smartscity", "baidu-default", "baidu-cached"})
    public String implementation;

    @Param({"standard", "high-throughput"})
    public String profile;

    private CachedUniqueIdGenerator smartscity;
    private DefaultUidGenerator baidu;

    @Setup(Level.Trial)
    public void start() throws Exception {
      boolean high = profile.equals("high-throughput");
      if (!high && !profile.equals("standard")) {
        throw new IllegalArgumentException(profile);
      }
      if (implementation.equals("smartscity")) {
        smartscity =
            new CachedUniqueIdGenerator(
                GeneratorOptions.builder()
                    .bits(high ? BitsAllocator.highThroughput() : BitsAllocator.standard())
                    .capacity(1 << ((high ? 17 : 15) + 1))
                    .build(),
                new LocalLease(),
                TimeSource.SYSTEM);
      } else {
        if (implementation.equals("baidu-cached")) {
          CachedUidGenerator cached = new CachedUidGenerator();
          cached.setBoostPower(1);
          baidu = cached;
        } else if (implementation.equals("baidu-default")) {
          baidu = new DefaultUidGenerator();
        } else {
          throw new IllegalArgumentException(implementation);
        }
        baidu.setTimeBits(30);
        baidu.setWorkerBits(high ? 16 : 18);
        baidu.setSeqBits(high ? 17 : 15);
        baidu.setEpochStr("2026-01-01");
        baidu.setWorkerIdAssigner(() -> 1L);
        baidu.afterPropertiesSet();
      }
    }

    /** Returns one ID; expected exhaustion is counted by the caller without retries. */
    public long next() {
      return smartscity != null ? smartscity.getUniqueId() : baidu.getUID();
    }

    @Override
    @TearDown(Level.Trial)
    public void close() throws Exception {
      if (smartscity != null) {
        smartscity.close();
      }
      if (baidu instanceof CachedUidGenerator) {
        ((CachedUidGenerator) baidu).destroy();
      }
    }
  }

  /**
   * Thread-confined outcomes distinguish timed-loop successes from failed attempts.
   *
   * @author jack
   */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Outcomes {
    public long ids;
    public long timeouts;
    public long rejections;
  }

  /** A process-local worker fixture; it makes no distributed allocation claim. */
  private static final class LocalLease implements WorkerLease {
    private volatile State state = State.ACTIVE;

    @Override
    public long rawId() {
      return 1;
    }

    @Override
    public long workerId() {
      return 1;
    }

    @Override
    public State state() {
      return state;
    }

    @Override
    public void close() {
      state = State.CLOSED;
    }
  }

  @Benchmark
  public long generate(Generator generator, Outcomes outcomes, Control control) {
    boolean measured = control.startMeasurement && !control.stopMeasurement;
    try {
      long id = generator.next();
      if (measured) {
        outcomes.ids++;
      }
      return id;
    } catch (IdException e) {
      if (e.getReason() != IdException.Reason.TIMEOUT) {
        throw e;
      }
      if (measured) {
        outcomes.timeouts++;
      }
      return 0;
    } catch (UidGenerateException e) {
      // Upstream wraps buffer exhaustion and internal failures in the same public exception.
      if (measured) {
        outcomes.rejections++;
      }
      return 0;
    }
  }
}
