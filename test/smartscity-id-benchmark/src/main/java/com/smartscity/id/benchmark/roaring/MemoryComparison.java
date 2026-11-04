package com.smartscity.id.benchmark.roaring;

import com.smartscity.id.core.RoaringIdEncoder;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import org.openjdk.jol.info.GraphLayout;
import org.roaringbitmap.ArrayContainer;
import org.roaringbitmap.BitmapContainer;
import org.roaringbitmap.Container;
import org.roaringbitmap.RunContainer;
import org.roaringbitmap.longlong.HighLowContainer;
import org.roaringbitmap.longlong.Roaring64Bitmap;

/**
 * Bounded deterministic steady-state datasets; no production scheduling claims.
 *
 * @author jack
 */
public final class MemoryComparison {
  private static final RoaringIdEncoder ENCODER = new RoaringIdEncoder();

  private MemoryComparison() {}

  static long id(long i, int q, int period, int w) {
    long g = (long) q * period, t = (i / g) * period, r = i % g;
    return ((100000 + t + r / 32768) << 33) | ((long) w << 15) | (r % 32768);
  }

  static long gc() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(x -> Math.max(0, x.getCollectionCount()))
        .sum();
  }

  static long allocated() {
    return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
        .getThreadAllocatedBytes(Thread.currentThread().getId());
  }

  static long peak() {
    return ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(x -> x.getType() == MemoryType.HEAP)
        .mapToLong(x -> x.getPeakUsage().getUsed())
        .sum();
  }

  static void resetPeak() {
    ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
  }

  static void emit(
      int workers,
      int q,
      int period,
      int seconds,
      double p,
      String encoding,
      boolean opt,
      Roaring64Bitmap bm,
      long generated,
      long inserted,
      long buildNs,
      long lookupNs,
      long alloc,
      long gcs,
      long optimizeNs,
      long peak)
      throws Exception {
    Field f = Roaring64Bitmap.class.getDeclaredField("highLowContainer");
    f.setAccessible(true);
    HighLowContainer h = (HighLowContainer) f.get(bm);
    Iterator<Container> it = h.containerIterator();
    long a = 0, b = 0, r = 0;
    while (it.hasNext()) {
      Container c = it.next();
      if (c instanceof ArrayContainer) {
        a++;
      } else if (c instanceof BitmapContainer) {
        b++;
      } else if (c instanceof RunContainer) {
        r++;
      } else {
        throw new AssertionError();
      }
    }
    long heap = GraphLayout.parseInstance(bm).totalSize();
    System.out.printf(
        Locale.ROOT,
        "%d,%d,%d,%d,%.2f,%s,%s,%d,%d,%d,%d,%d,%d,%d,%.4f,%d,%d,%d,%.4f,%d,%d,%d,%d,%d,%d%n",
        workers,
        q,
        period,
        seconds,
        p,
        encoding,
        opt,
        generated,
        inserted,
        bm.getLongCardinality(),
        a + b + r,
        a,
        b,
        r,
        (double) inserted / (a + b + r),
        bm.getSizeInBytes(),
        bm.serializedSizeInBytes(),
        heap,
        (double) heap / inserted,
        buildNs,
        lookupNs,
        alloc,
        gcs,
        optimizeNs,
        peak);
  }

  public static void main(String[] args) throws Exception {
    System.out.println(
        "workers,tps,period,seconds,p,encoding,runOptimize,generatedIds,insertedIds,cardinality,containers,array,bitmap,run,averageIdsPerContainer,getSizeInBytes,serializedBytes,estimatedHeapBytes,bytesPerId,buildNanos,lookupNanos,allocatedBytes,gcCount,optimizeNanos,peakPoolSumEstimate");
    int[][] loads = {
      {1, 10}, {1, 100}, {1, 1000}, {1, 10000}, {100, 10}, {100, 1000}, {100, 10000}
    };
    for (int[] load : loads) {
      for (int period : new int[] {1, 10}) {
        for (double p : new double[] {1, .1, .01}) {
          int w = load[0], q = load[1], seconds = Math.min(1800, Math.max(10, 1000000 / (w * q)));
          seconds = seconds / 10 * 10;
          long generated = (long) w * q * seconds;
          long[] ids = new long[(int) Math.min(generated, Math.ceil(generated * p * 1.1 + 1024))];
          int n = 0;
          Random random = new Random(711);
          for (int worker = 0; worker < w; worker++) {
            for (long i = 0; i < (long) q * seconds; i++) {
              if (random.nextDouble() < p) {
                if (n == ids.length) {
                  ids = Arrays.copyOf(ids, n + n / 2 + 1);
                }
                ids[n++] = id(i, q, period, worker);
              }
            }
          }
          for (String enc : new String[] {"standard", "roaring"}) {
            Roaring64Bitmap bm = new Roaring64Bitmap();
            resetPeak();
            long g0 = gc(), a0 = allocated(), start = System.nanoTime();
            for (int i = 0; i < n; i++) {
              long original = ids[i],
                  key = enc.equals("roaring") ? ENCODER.encode(original) : original;
              if (ENCODER.decode(ENCODER.encode(original)) != original) {
                throw new AssertionError();
              }
              bm.addLong(key);
            }
            long build = System.nanoTime() - start,
                alloc = allocated() - a0,
                gcs = gc() - g0,
                peakBuild = peak();
            if (bm.getLongCardinality() != n) {
              throw new AssertionError("collision");
            }
            start = System.nanoTime();
            for (int i = 0; i < n; i++) {
              if (!bm.contains(enc.equals("roaring") ? ENCODER.encode(ids[i]) : ids[i])) {
                throw new AssertionError("membership");
              }
            }
            long lookup = System.nanoTime() - start;
            emit(
                w, q, period, seconds, p, enc, false, bm, generated, n, build, lookup, alloc, gcs,
                0, peakBuild);
            resetPeak();
            start = System.nanoTime();
            bm.runOptimize();
            long opt = System.nanoTime() - start;
            emit(
                w, q, period, seconds, p, enc, true, bm, generated, n, build, lookup, alloc, gcs,
                opt, peak());
            for (int i = 0; i < Math.min(100, n); i++) {
              long key = enc.equals("roaring") ? ENCODER.encode(ids[i]) : ids[i];
              bm.removeLong(key);
              if (bm.contains(key)) {
                throw new AssertionError("deletion");
              }
            }
            if (bm.getLongCardinality() != n - Math.min(n, 100)) {
              throw new AssertionError();
            }
          }
        }
      }
    }
  }
}
