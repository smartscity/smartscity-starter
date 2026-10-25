# Benchmark usage

JMH remains opt-in and outside production dependencies. `./mvnw -Pbenchmarks -pl :smartscity-id-benchmark -am package` builds the executable without running measurements. `java -jar test/smartscity-id-benchmark/target/benchmarks.jar -l` verifies discovery only.

Existing `IdBenchmark`, `BenchmarkGenerator` and `FixedRateProbe` reuse the production algorithm unchanged. The local bounded runner is `python3 test/run-benchmarks.py --run --output <new-directory>`; it records environment, fingerprints and partial results under an eight-minute budget.

The manual `.github/workflows/id-benchmark.yml` selects hosted or explicitly labeled self-hosted Linux execution. It serializes JDK 17/21 jobs, builds with JDK 21, runs three profiles at 1/4/8 threads and three bounded fixed-rate trials. Each job has a 25-minute limit; measurement step has 10 minutes. `run-benchmark-ci.sh` preserves failures/partial output and reports the strict 100k target only when explicitly selected. `check-benchmark-ci.sh` exercises result validation and failure orchestration with synthetic data; it is not a performance measurement.

Comparisons require recorded source and artifact fingerprints, matching CPU, OS, JDK, JVM options, thread count and warmup/measurement/forks, and reported allocation rates. Encoding capacity is not throughput. The [recorded Smartscity benchmark](../../docs/id/benchmark.md) includes its environment, uncertainty and failures; it is a local snapshot, not production capacity.

The [isolated comparison fixture](comparison/README.md) remains available for opt-in matched-layout runs. Its generated Markdown/HTML reports show Smartscity measurements only; raw results retain all executed scenarios. Render saved results without launching Java using `python3 test/smartscity-id-benchmark/comparison/report.py --input <run-directory>`.

Generated run directories belong under ignored `test/evidence/` or outside the repository. Retain only curated benchmark results in maintained documentation.
