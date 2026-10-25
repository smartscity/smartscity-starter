# Benchmark usage

JMH remains opt-in and outside production dependencies. `./mvnw -Pbenchmarks -pl :smartscity-id-benchmark -am package` builds the executable without running measurements. `java -jar test/smartscity-id-benchmark/target/benchmarks.jar -l` verifies discovery only.

Existing `IdBenchmark`, `BenchmarkGenerator` and `FixedRateProbe` reuse the production algorithm unchanged. The local bounded runner is `python3 test/run-benchmarks.py --run --output <new-directory>`; it records environment, fingerprints and partial results under an eight-minute budget. Do not run sustained tests as part of this refactor.

The manual `.github/workflows/id-benchmark.yml` selects hosted or explicitly labeled self-hosted Linux execution. It serializes JDK 17/21 jobs, builds with JDK 21, runs three profiles at 1/4/8 threads and three bounded fixed-rate trials. Each job has a 25-minute limit; measurement step has 10 minutes. `run-benchmark-ci.sh` preserves failures/partial output and reports the strict 100k target only when explicitly selected. `check-benchmark-ci.sh` exercises result validation and failure orchestration with synthetic data; it is not a performance measurement.

Comparisons require recorded before/after source and artifact fingerprints, matching CPU, OS, JDK, JVM options, thread count and warmup/measurement/forks, and reported allocation rates. Encoding capacity is not throughput. Historical measured results in `test/evidence/id-benchmark-20261025` are retained and are not a post-refactor performance claim. No performance improvement is inferred from source cleanup. See the [current verification report](../../docs/testing/verification.md) for the bounded configuration-refactor comparison.

The [Baidu UID Generator comparison](comparison/README.md) provides an isolated, pinned-source fixture for matched-layout local JMH runs. It records successful IDs, failures, allocation and timestamp semantics separately; the initial historical no-future implementation has been superseded by cached logical time under ADR-0011. See the `2026-10-25 report` (not present in this checkout).

The [optimization comparison](../evidence/uid-optimization-20261025/REPORT.md) separates the behavior restoration from subsequent hot-path changes using frozen SDK variants and corrected timed-loop outcome counters.
