package com.smartscity.id.benchmark;

import com.smartscity.id.api.IdException;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Control;

/**
 * One SDK shared by all consumers; attempts and successful IDs are distinct metrics.
 *
 * @author jack
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(
    value = 1,
    jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class IdBenchmark {
  @State(Scope.Benchmark)
  public static class Sdk {
    @Param({"standard", "javascript", "high-throughput"})
    public String profile;

    CachedUniqueIdGenerator generator;

    @Setup(Level.Trial)
    public void start() {
      generator = BenchmarkGenerator.create(profile);
    }

    @TearDown(Level.Trial)
    public void stop() {
      generator.close();
    }
  }

  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Outcomes {
    public long ids;
    public long timeouts;
  }

  @Benchmark
  public long generate(Sdk sdk, Outcomes outcomes, Control control) {
    boolean measured = control.startMeasurement && !control.stopMeasurement;
    try {
      long id = sdk.generator.getUniqueId();
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
    }
  }
}
