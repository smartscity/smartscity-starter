package com.smartscity.id.benchmark.roaring;

import com.smartscity.id.core.RoaringIdEncoder;
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
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * Single-writer storage replay with identical original IDs and explicit encoding cost. @author jack
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BitmapBenchmark {
  @Param({"10", "10000"})
  public int tps;

  @Param({"standard", "roaring"})
  public String encoding;

  private final long[] ids = new long[10000];
  private final RoaringIdEncoder encoder = new RoaringIdEncoder();
  private Roaring64Bitmap populated;
  private int cursor;

  @Setup(Level.Trial)
  public void setup() {
    populated = new Roaring64Bitmap();
    for (int i = 0; i < ids.length; i++) {
      ids[i] = ((100000L + i / tps) << 33) | (7L << 15) | (i % tps);
      populated.addLong(key(ids[i]));
    }
  }

  private long key(long id) {
    return encoding.equals("roaring") ? encoder.encode(id) : id;
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  public long add() {
    Roaring64Bitmap b = new Roaring64Bitmap();
    for (long id : ids) {
      b.addLong(key(id));
    }
    return b.getLongCardinality();
  }

  @Benchmark
  public boolean contains() {
    return populated.contains(key(ids[cursor++ % ids.length]));
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  public long replayEndToEnd() {
    Roaring64Bitmap b = new Roaring64Bitmap();
    long found = 0;
    for (long id : ids) {
      long key = key(id);
      b.addLong(key);
      if (b.contains(key)) {
        found++;
      }
    }
    return found;
  }
}
