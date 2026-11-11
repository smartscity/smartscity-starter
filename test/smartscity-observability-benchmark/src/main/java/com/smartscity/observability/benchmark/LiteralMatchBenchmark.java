package com.smartscity.observability.benchmark;

import com.smartscity.observability.LiteralMasker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Compares compiled AC matching with sequential replacement on equal, nonoverlapping inputs.
 * Dictionary construction and semantic equivalence checks run outside measurement.
 *
 * @author jack
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class LiteralMatchBenchmark {
  @Param({"64", "1024"})
  public int dictionarySize;

  @Param({"1024", "8192"})
  public int inputSize;

  @Param({"HIT", "MISS"})
  public String match;

  private List<String> words;
  private LiteralMasker masker;
  private String input;

  @Setup
  public void setup() {
    words = new ArrayList<>();
    for (int i = 0; i < dictionarySize; i++) {
      words.add(String.format(java.util.Locale.ROOT, "secret%06d", i));
    }
    String prefix =
        match.equals("HIT") ? words.get(0) + " " + words.get(dictionarySize - 1) + " " : "";
    input = prefix + "x".repeat(inputSize - prefix.length());
    masker = new LiteralMasker(words, 65536);
    if (!ac().equals(sequentialReplace())) {
      throw new IllegalStateException("Comparison outputs differ");
    }
  }

  @Benchmark
  public String ac() {
    return masker.mask(input);
  }

  @Benchmark
  public String sequentialReplace() {
    String result = input;
    for (String word : words) {
      result = result.replace(word, "***");
    }
    return result;
  }
}
