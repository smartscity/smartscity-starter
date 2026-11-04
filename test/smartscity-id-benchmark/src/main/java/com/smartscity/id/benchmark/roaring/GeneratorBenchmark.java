package com.smartscity.id.benchmark.roaring;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.core.BufferedIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.core.RoaringIdEncoder;
import com.smartscity.id.core.RoaringIdGenerator;
import com.smartscity.id.core.SequenceIdGenerator;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Shared-instance and independent-worker allocation, excluding startup.
 *
 * @author jack
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class GeneratorBenchmark {
  /**
   * Shared configuration and optional shared generator.
   *
   * @author jack
   */
  @State(Scope.Benchmark)
  public static class Shared {
    @Param({"sequence", "buffered", "roaring"})
    public String kind;

    @Param({"shared", "independent"})
    public String ownership;

    IdGenerator base, view;

    @Setup(Level.Trial)
    public void setup() {
      if (ownership.equals("shared")) {
        base = create(kind, 1);
        view = wrap(kind, base);
      }
    }

    @TearDown(Level.Trial)
    public void close() {
      if (view != null) {
        view.close();
      }
      if (base != null) {
        base.close();
      }
    }
  }

  /**
   * Per-thread generator is used only for independent workers.
   *
   * @author jack
   */
  @State(Scope.Thread)
  public static class Local {
    IdGenerator base, view;

    @Setup(Level.Trial)
    public void setup(Shared s, ThreadParams t) {
      if (s.ownership.equals("independent")) {
        base = create(s.kind, t.getThreadIndex() + 1);
        view = wrap(s.kind, base);
      }
    }

    @TearDown(Level.Trial)
    public void close() {
      if (view != null) {
        view.close();
      }
      if (base != null) {
        base.close();
      }
    }
  }

  /**
   * Outcomes are explicit: attempt rates must not be called successful ID rates.
   *
   * @author jack
   */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Outcomes {
    public long successes;
    public long timeouts;
    public long otherFailures;
  }

  @Benchmark
  public void generate(Shared s, Local l, Outcomes o, Blackhole bh) {
    try {
      bh.consume((s.view != null ? s.view : l.view).getUniqueId());
      o.successes++;
    } catch (IdException ex) {
      if (ex.getReason() == IdException.Reason.TIMEOUT) {
        o.timeouts++;
      } else {
        o.otherFailures++;
        throw ex;
      }
    }
  }

  static IdGenerator wrap(String kind, IdGenerator base) {
    return kind.equals("roaring") ? new RoaringIdGenerator(base, new RoaringIdEncoder()) : base;
  }

  static IdGenerator create(String kind, long worker) {
    WorkerLease lease =
        new WorkerLease() {
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
    GeneratorOptions options = GeneratorOptions.builder().build();
    return kind.equals("sequence")
        ? new SequenceIdGenerator(options, lease, TimeSource.SYSTEM)
        : new BufferedIdGenerator(options, lease, TimeSource.SYSTEM);
  }
}
