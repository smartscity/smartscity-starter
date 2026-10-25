# Smartscity ID benchmark

## Run status

Saved run: uid-local-20261025-205729. This view contains only Smartscity measurements.

Recorded run status: MEASURED. Completed Smartscity scenarios: 6/6. Source fingerprint: UNCHANGED.

## Measurement details

The +/- column is JMH's 99.9% confidence-interval half-width for successful IDs/s, not request latency. Timeout and rejection columns are failed calls per second. B/success is estimated allocation per successful ID, including producer allocation; smaller is better. Primary JMH operations count attempts, not necessarily successful IDs.

| Profile         | Threads | Implementation | IDs/s      | +/- IDs/s | Timeout/s | Rejection/s | B/success | Allocation MiB/s |
| --------------- | ------- | -------------- | ---------- | --------- | --------- | ----------- | --------- | ---------------- |
| standard        | 1       | Smartscity     | 11,290,111 | 2,336,603 | 0.000     | 0.000       | 0.000360  | 0.004            |
| standard        | 4       | Smartscity     | 10,173,985 | 2,108,026 | 0.333     | 0.000       | 0.000710  | 0.007            |
| standard        | 8       | Smartscity     | 9,465,019  | 1,805,449 | 2.412     | 0.000       | 0.001230  | 0.011            |
| high-throughput | 1       | Smartscity     | 11,119,432 | 2,160,329 | 0.000     | 0.000       | 0.000368  | 0.004            |
| high-throughput | 4       | Smartscity     | 12,455,210 | 318,335   | 0.000     | 0.000       | 0.000509  | 0.006            |
| high-throughput | 8       | Smartscity     | 10,210,708 | 462,544   | 0.000     | 0.000       | 0.000887  | 0.009            |

## Correctness probes (separate from JMH throughput)

These bounded, process-local probes check duplicate IDs and future timestamp lead. They do not prove distributed uniqueness or sustained latency. Missing probes are NOT_RUN.

| Implementation | Status | Attempts | IDs     | Failures | Duplicates | Max future seconds |
| -------------- | ------ | -------- | ------- | -------- | ---------- | ------------------ |
| Smartscity     | PASS   | 100,000  | 100,000 | 0        | 0          | 3                  |

## Environment and reproducibility

| Setting              | Recorded value                                                                                                                                                                                      |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CPU                  | Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz                                                                                                                                                            |
| Logical CPUs         | 12                                                                                                                                                                                                  |
| OS                   | macOS-26.5.1-x86_64-i386-64bit-Mach-O                                                                                                                                                               |
| Java                 | openjdk version "21.0.12.1" 2026-08-18 LTS OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS) OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS, mixed mode) |
| JVM options          | -Xms512m -Xmx512m -Duser.timezone=UTC                                                                                                                                                               |
| Forks                | 2                                                                                                                                                                                                   |
| Warmup               | 2 x 1s                                                                                                                                                                                              |
| Measurement          | 3 x 2s                                                                                                                                                                                              |
| Profiler             | gc                                                                                                                                                                                                  |
| Measured JAR SHA-256 | 9186193ea744eac2b17200d237c5d20805b898896e066afedb06c45d6aa1ba0f                                                                                                                                    |

Layouts: standard 30/18/15 bits with 65,536 cache slots; high-throughput 30/16/17 bits with 262,144 slots; epoch 2026-01-01 UTC and worker 1. Cached IDs can borrow future logical seconds. This is a short local microbenchmark, not production capacity or a comparison against an earlier run.

This report is derived from saved measurements; rendering it runs no benchmark. The source fingerprint describes the measurement interval, not subsequent edits.
