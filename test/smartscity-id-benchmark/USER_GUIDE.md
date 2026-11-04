# Benchmark usage

JMH remains opt-in and outside production dependencies. `./mvnw -Pbenchmarks -pl :smartscity-id-benchmark -am package` builds the executable without running measurements. `java -jar test/smartscity-id-benchmark/target/benchmarks.jar -l` verifies discovery only.

Existing `IdBenchmark`, `BenchmarkGenerator` and `FixedRateProbe` reuse the production algorithm unchanged. The local bounded runner is `python3 test/run-benchmarks.py --run --output <new-directory>`; it records environment, fingerprints and partial results under an eight-minute budget.

The manual `.github/workflows/id-benchmark.yml` selects hosted or explicitly labeled self-hosted Linux execution. It serializes JDK 17/21 jobs, builds with JDK 21, runs three profiles at 1/4/8 threads and three bounded fixed-rate trials. Each job has a 25-minute limit; measurement step has 10 minutes. `run-benchmark-ci.sh` preserves failures/partial output and reports the strict 100k target only when explicitly selected. `check-benchmark-ci.sh` exercises result validation and failure orchestration with synthetic data; it is not a performance measurement.

Comparisons require recorded source and artifact fingerprints, matching CPU, OS, JDK, JVM options, thread count and warmup/measurement/forks, and reported allocation rates. Encoding capacity is not throughput. The [recorded Smartscity benchmark](../../docs/id/benchmark.md) includes its environment, uncertainty and failures; it is a local snapshot, not production capacity.

The [isolated comparison fixture](comparison/README.md) remains available for opt-in matched-layout runs. Its generated Markdown/HTML reports compare Smartscity, Baidu Cached and Baidu Default across all 18 scenarios, including failures and separate external 10 ms completion probes. Render saved results without launching Java using `python3 test/smartscity-id-benchmark/comparison/report.py --input <run-directory>`.

Generated run directories belong under ignored `test/evidence/` or outside the repository. Retain only curated benchmark results in maintained documentation.

## Generator, encoder and Bitmap comparison

Build the existing opt-in module with Java 21. Startup/registry I/O is outside generator measurement; local fixtures represent distinct allocated workers and do not test distributed registration.

```sh
export JAVA_HOME=/Users/liyunlong/.sdkman/candidates/java/21.0.12+1.1-tem
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -Pbenchmarks -pl :smartscity-id-benchmark -am -Dmaven.test.skip=true package
mkdir -p test/evidence/roaring-local
java -jar test/smartscity-id-benchmark/target/benchmarks.jar \
  '.*roaring.GeneratorBenchmark.generate' -bm thrpt -t 1 \
  -f 3 -wi 5 -w 5s -i 10 -r 5s -prof gc \
  -rf json -rff test/evidence/roaring-local/generator-throughput.json
java -jar test/smartscity-id-benchmark/target/benchmarks.jar \
  '.*roaring.GeneratorBenchmark.generate' -bm sample -t 1 \
  -f 3 -wi 5 -w 5s -i 10 -r 5s -prof gc \
  -rf json -rff test/evidence/roaring-local/generator-latency.json
java -jar test/smartscity-id-benchmark/target/benchmarks.jar \
  '.*roaring.EncoderBenchmark.*' -bm avgt -t 1 -f 3 -wi 5 -i 10 -prof gc
java -jar test/smartscity-id-benchmark/target/benchmarks.jar \
  '.*roaring.BitmapBenchmark.*' -bm thrpt -t 1 -f 3 -wi 5 -i 10 -prof gc
java -jar test/smartscity-id-benchmark/target/benchmarks.jar \
  '.*roaring.LivePipelineBenchmark.*' -bm sample -t 1 -f 3 -wi 5 -i 10 -prof gc
java -Xmx2g -cp test/smartscity-id-benchmark/target/benchmarks.jar \
  com.smartscity.id.benchmark.roaring.MemoryComparison \
  > test/evidence/roaring-local/memory.csv
```

Repeat generator commands with `-t 2`, `4`, `8`, `16`, `32`; report oversubscription. The `ownership` parameter selects one shared generator or independent thread-local generators with distinct worker IDs. Throughput counts attempts; successes and timeouts are separate exact auxiliary counters. SampleTime reports sampled call latency, including failed attempts; its mean is not the reciprocal of throughput. Extreme quantiles from short runs are exploratory.

Encoder inputs are changing pre-generated arrays. Storage replay uses the identical original arrays for both representations and one writer per Bitmap. `add` measures construction batches normalized to IDs; `contains` measures individual existing-key lookups. `replayEndToEnd` excludes live generation. `LivePipelineBenchmark` includes allocation/add/contains for identical fixed-clock 4096-ID prefixes; invocation setup/cleanup excludes generator construction from the timed method, but GC profiler and process/iteration accounting can include fixture work. Do not call that fixture allocation pure pipeline allocation. Batch SampleTime percentiles are per-batch amortized values, not individually timed add tail latency. Standalone MemoryComparison's build/lookup timers are diagnostic one-pass measurements, not a replacement for JMH.

MemoryComparison verifies inverse mapping, cardinality, membership and deletion using identical selected original IDs. It compares standard/encoded, before/after runOptimize, with deterministic seed 711, uniform/10-second-burst steady allocation. The measured window is bounded to 10..1800 seconds depending on source rate; 100-worker high-rate cases use 10 seconds, not fictitious multi-day materialization. Its `peakPoolSumEstimate` sums independent memory-pool high-water marks and includes other live objects, so it is neither a precise simultaneous peak nor retained Bitmap heap. JOL fallback values are estimates. Actual retained heap and process RSS are unavailable in the recorded run.

Optional `roaring/run.py` reproduces the bounded exploratory matrix and records commands, source/JAR fingerprints and per-group host observations. It intentionally uses shorter settings than the commands above. `roaring/model.py` separately models 30-minute/1-day/7-day/30-day steady windows; it never labels extrapolation as measurement. `roaring/report.py` generates the canonical report and charts from saved results; set `JFREECHART_JAR` to a downloaded `org.jfree:jfreechart:1.5.5` JAR to render charts with Java2D. Detailed data lives under `roaring/results`; the [canonical report](../../docs/id/benchmark.md) states limitations and measured counterexamples.

Chart rendering after the measurement run:

```sh
curl -fL --retry 2 -o /tmp/jfreechart-1.5.5.jar https://repo.maven.apache.org/maven2/org/jfree/jfreechart/1.5.5/jfreechart-1.5.5.jar
JFREECHART_JAR=/tmp/jfreechart-1.5.5.jar python3 test/smartscity-id-benchmark/roaring/report.py
```
