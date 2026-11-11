# Observability benchmark contract

Execution was authorized on 2026-11-12. Functional verification and measured performance are separate gates. Raw results are retained even on timeout or failure; no release or production SLA follows from this experiment.

## Experiment budget

| Experiment | Matrix | Warmup / measurement | Repeats | Boundary |
|---|---|---|---|---|
| Literal masking | AC and sequential replacement; 64/1024 literals; 1024/8192 ASCII characters; HIT/MISS | 3 x 1 s / 5 x 1 s | 2 JVM forks, one thread | 16 cases; precompiled dictionary, equal outputs checked outside timing |
| Pipeline | Credential text masking, sanitized pipe encoding, Logback with MDC; 1024/8192 characters | 3 x 1 s / 5 x 1 s | 2 JVM forks, one thread | 6 cases; bounded last-record sink excludes disk/network |
| Correlation | Standalone root / child creation | 3 x 1 s / 5 x 1 s | 2 JVM forks, one thread | 2 cases; secure random included; no native provider |
| UDP sender | 10k/50k/100k offered lines/s | Separate 2 s warmup / 8 s measurement | 2 trials | One producer, one socket writer, one loopback receiver; queue 8192, packet 1400 bytes |

JMH uses throughput and the GC profiler, with 256 MiB initial / 512 MiB maximum heap. The runner bounds JMH to 900 seconds and UDP to 180 seconds; the Actions performance job is limited to 25 minutes. The complete workflow is expected to take 15-25 minutes, excluding queue time. Shared Ubuntu 24.04 runner measurements are reference evidence, not a capacity guarantee. Environment, exact revision, JAR hashes, raw per-fork results and return codes accompany each report.

## Ownership and validity

| Owner | State and lifetime | Failure handling |
|---|---|---|
| JMH thread state | Prepared inputs and immutable compiled policies, one trial | Setup asserts semantic equivalence; exceptions fail the run |
| Logback benchmark | One attached appender and encoder; only the last byte array retained | Detach/stop owned objects; never stop the shared LoggerContext |
| UDP trial | Owned ephemeral receiver, sender, reader, fixed sample array | Bounded close/join; unexpected receiver errors or nonempty queue fail |
| Python runner | Process group, raw output and report | Timeout terminates the owned process group and keeps partial evidence |

Reuse JMH state/lifecycle and the production sender; no alternative queue or benchmark-only production strategy is introduced. Every input is synthetic. Sequential replacement is a baseline only for the nonoverlapping equal-output dataset, not an implementation of general overlap masking.

The UDP report separates attempted lines, local drops (queue rejection, validation, socket failure or shutdown), locally written lines, actual received lines and delivery gaps. It records sampled admission p50/p99, send errors and close duration. Admission samples include accepted and rejected calls; they are not end-to-end latency. Achieved offered load below 95% of target is UNDERDRIVEN. Loss does not disappear into a successful throughput score. This probe does not measure statsd_exporter capacity; real collector semantics are verified separately in the functional matrix.

## Reproduction

```sh
./mvnw -Pbenchmarks -pl test/smartscity-observability-benchmark -am package -DskipTests
python3 test/smartscity-observability-benchmark/run.py --output test/evidence/observability-performance-local
```

Use a new output directory for every invocation. Add `--smoke` only for harness validation (one fork, one 100 ms warmup/measurement and one-second UDP trials); its result is labeled SMOKE and must not be quoted as a benchmark baseline.

The [Actions workflow](../../.github/workflows/observability-verification.yml) gates performance on production contract tests and independent Boot 2.7.18 / 3.5.16 / 4.0.9 consumers on JDK 17 and 21. Real statsd_exporter 0.28.0 is checksum-pinned. Missing reports or unexpected skips fail the functional gate. The Boot 2 profile intentionally skips the native-provider test because it has no provider. Benchmark execution is manual or restricted to `codex/observability-validation-*` branch pushes; routine CI only builds/discovers benchmark classes.
