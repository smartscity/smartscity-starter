package com.smartscity.observability.benchmark;

import com.smartscity.observability.CorrelationContext;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Measures standalone secure-random root and child identity creation, without provider I/O.
 *
 * @author jack
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class CorrelationBenchmark {
  private CorrelationContext parent;

  @Setup
  public void setup() {
    parent = CorrelationContext.root("benchmark");
  }

  @Benchmark
  public CorrelationContext root() {
    return CorrelationContext.root("benchmark");
  }

  @Benchmark
  public CorrelationContext clientChild() {
    return parent.child("benchmark", "receiver");
  }
}
