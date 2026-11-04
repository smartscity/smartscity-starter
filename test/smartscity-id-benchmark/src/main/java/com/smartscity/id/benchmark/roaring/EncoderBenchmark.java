package com.smartscity.id.benchmark.roaring;

import com.smartscity.id.core.IdentityIdEncoder;
import com.smartscity.id.core.RoaringIdEncoder;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Isolates permutation cost using changing pre-generated inputs.
 *
 * @author jack
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EncoderBenchmark {
  private final IdentityIdEncoder identity = new IdentityIdEncoder();
  private final RoaringIdEncoder roaring = new RoaringIdEncoder();
  private final long[] original = new long[65536], encoded = new long[65536];
  private int cursor;

  @Setup
  public void setup() {
    Random r = new Random(711);
    for (int i = 0; i < original.length; i++) {
      original[i] = (r.nextLong() & Long.MAX_VALUE) | 1;
      encoded[i] = roaring.encode(original[i]);
    }
  }

  @Benchmark
  public long identity() {
    return identity.encode(original[cursor++ & 65535]);
  }

  @Benchmark
  public long encode() {
    return roaring.encode(original[cursor++ & 65535]);
  }

  @Benchmark
  public long decode() {
    return roaring.decode(encoded[cursor++ & 65535]);
  }

  @Benchmark
  public long roundTrip() {
    return roaring.decode(roaring.encode(original[cursor++ & 65535]));
  }
}
