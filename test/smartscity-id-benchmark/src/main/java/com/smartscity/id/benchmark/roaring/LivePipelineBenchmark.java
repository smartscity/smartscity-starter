package com.smartscity.id.benchmark.roaring;

import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.core.BufferedIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.core.RoaringIdEncoder;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * Fixed-clock identical 4096-ID prefixes: live allocation, encoding, add and contains. @author jack
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LivePipelineBenchmark {
  @Param({"standard", "roaring"})
  public String encoding;

  private IdGenerator generator;
  private final RoaringIdEncoder encoder = new RoaringIdEncoder();

  @Setup(Level.Invocation)
  public void setup() {
    generator =
        new BufferedIdGenerator(
            GeneratorOptions.builder().build(),
            new WorkerLease() {
              public long rawId() {
                return 7;
              }

              public long workerId() {
                return 7;
              }

              public State state() {
                return State.ACTIVE;
              }

              public void close() {}
            },
            new TimeSource() {
              public long wallTimeMillis() {
                return 1800000000000L;
              }

              public long nanoTime() {
                return 0;
              }
            });
  }

  @TearDown(Level.Invocation)
  public void close() {
    generator.close();
  }

  @Benchmark
  @OperationsPerInvocation(4096)
  public long pipeline() {
    Roaring64Bitmap b = new Roaring64Bitmap();
    long found = 0;
    for (int i = 0; i < 4096; i++) {
      long id = generator.getUniqueId(), key = encoding.equals("roaring") ? encoder.encode(id) : id;
      b.addLong(key);
      if (b.contains(key)) {
        found++;
      }
    }
    return found;
  }
}
