# Smartscity ID versus Baidu UID Generator

Date: 2026-10-25 (Asia/Singapore). Scope: bounded local generation experiment, explicitly requested by the user. Measurement status: **NOT_COMPARABLE**, 18/18 JMH scenarios, 3/3 independent probes, 419.0 seconds including probes. This is not a sustained production baseline.

**Snapshot qualification:** All 18 measurements completed against the recorded benchmark JAR; its initial, final and retained-snapshot SHA-256 values match. Concurrent edits changed six SDK source files, so the runner conservatively retained `NOT_COMPARABLE` for the changing working tree. These tables describe the recorded artifact, not acceptance of the latest repository. No measurement was rerun to hide the drift. A separate JDK 21 compilation of current SDK sources and `javap -p -c -s -constants` comparison found identical instructions, signatures and constants in all 23 SDK classes, with stable source hashes during that check. This excludes debug line tables and source comments; it does not replace full testing. See `source-drift-review.json`, `measured-sdk-javap.txt` and `current-sdk-javap.txt`.


## Findings

Baidu Cached returns substantially more IDs per wall-clock second in this experiment. Smartscity enforces a different contract: it does not borrow future seconds, so its configured sequence capacity bounds sustained generation. The standard and high-throughput formats allow 32,768 and 131,072 IDs per real second respectively. A larger cache cannot lift that limit. Changing this policy would require a separate design decision; no algorithm or production option was changed here.

The table counts successful API returns, not attempted calls and not independently proven unique IDs for every JMH operation. Compare the failure and probe tables before interpreting throughput. Short windows, timestamp boundaries and Baidu Default's blocking calls can produce apparent rates above encoding capacity; these are not evidence of sustainable capacity beyond the format limit. Error bars are wide for some upstream cases, so these data do not establish reliable consumer-scaling rankings.

## Raw auxiliary successful-return rates (see correction below)

| Layout | Consumers | Smartscity Cached | Baidu Default | Baidu Cached |
|---|---:|---:|---:|---:|
| standard | 1 | 32,553 | 34,844 | 4,186,846 |
| standard | 4 | 32,752 | 37,315 | 4,728,300 |
| standard | 8 | 32,807 | 35,645 | 3,805,837 |
| high-throughput | 1 | 130,420 | 147,243 | 7,042,158 |
| high-throughput | 4 | 131,286 | 136,348 | 4,430,147 |
| high-throughput | 8 | 131,162 | 136,309 | 4,221,904 |

The two cached implementations share each layout and cache capacity. Baidu Default has no cache. No single 'times faster' score is reported: time borrowing, failure behavior, checks and resource policies differ materially.

## Raw auxiliary rates, failures and allocation (see correction below)

| Layout | Consumers | Implementation | Successful returns/s ± 99.9% error | Failed attempts/s | Failure % | B/attempt | Estimated B/return | Allocation MiB/s |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| standard | 1 | smartscity | 32,553 ± 103 | 91.81 | 0.281 | 2.17 | 2.18 | 0.07 |
| standard | 1 | baidu-default | 34,844 ± 13,729 | 0.00 | 0.000 | 0.08 | 0.08 | 0.00 |
| standard | 1 | baidu-cached | 4,186,846 ± 1,573,468 | 2.49 | 0.000 | 41.27 | 41.27 | 163.33 |
| standard | 4 | baidu-default | 37,315 ± 19,055 | 0.00 | 0.000 | 0.13 | 0.12 | 0.00 |
| standard | 4 | baidu-cached | 4,728,300 ± 2,435,738 | 5,483.17 | 0.116 | 103.01 | 102.69 | 398.08 |
| standard | 4 | smartscity | 32,752 ± 122 | 385.73 | 1.164 | 8.66 | 8.76 | 0.27 |
| standard | 8 | baidu-cached | 3,805,837 ± 4,043,997 | 36,746.43 | 0.960 | 136.44 | 137.22 | 369.51 |
| standard | 8 | smartscity | 32,807 ± 93 | 762.91 | 2.273 | 16.82 | 17.21 | 0.53 |
| standard | 8 | baidu-default | 35,645 ± 19,151 | 0.00 | 0.000 | 0.17 | 0.15 | 0.01 |
| high-throughput | 1 | baidu-default | 147,243 ± 81,873 | 0.00 | 0.000 | 0.02 | 0.02 | 0.00 |
| high-throughput | 1 | baidu-cached | 7,042,158 ± 636,287 | 0.00 | 0.000 | 42.76 | 42.76 | 286.51 |
| high-throughput | 1 | smartscity | 130,420 ± 656 | 81.84 | 0.063 | 0.49 | 0.49 | 0.06 |
| high-throughput | 4 | baidu-cached | 4,430,147 ± 3,051,525 | 356.80 | 0.008 | 56.32 | 56.31 | 204.28 |
| high-throughput | 4 | smartscity | 131,286 ± 1,727 | 354.02 | 0.269 | 2.01 | 2.01 | 0.25 |
| high-throughput | 4 | baidu-default | 136,348 ± 91,103 | 0.00 | 0.000 | 0.04 | 0.04 | 0.01 |
| high-throughput | 8 | smartscity | 131,162 ± 573 | 706.66 | 0.536 | 3.97 | 3.99 | 0.49 |
| high-throughput | 8 | baidu-default | 136,309 ± 88,496 | 0.00 | 0.000 | 0.06 | 0.06 | 0.01 |
| high-throughput | 8 | baidu-cached | 4,221,904 ± 3,032,184 | 2,133.12 | 0.051 | 58.23 | 58.19 | 210.54 |

`smartscity` failures above are TIMEOUT; its other errors abort the measurement. Baidu failures are `UidGenerateException`: upstream wraps both buffer exhaustion and internal failures in that type, so the counter cannot classify every failure as ordinary exhaustion. No retries are hidden. JMH primary throughput is attempts/s; the `ids` auxiliary metric supplies successful returns/s. B/attempt is JMH `gc.alloc.rate.norm`; estimated B/return is B/attempt multiplied by attempts/s divided by successful returns/s. These allocation figures include observed background producer allocation, not only the consumer call. Logging is disabled with an SLF4J NOP binding, but upstream logging argument construction remains unchanged.

JMH errors are the reported 99.9% confidence half-widths across six measurement samples (two forks). They are not latency percentiles or guarantees. Preserve the raw JSON for individual fork/iteration data rather than treating the means as exact constants.

## Separate correctness and timestamp probes

| Implementation | Status | Attempts | Successful returns | Failures | Duplicates | Maximum future seconds | Success p50 / p99 (µs) |
|---|---|---:|---:|---:|---:|---:|---:|
| smartscity | PASS | 100,000 | 99,224 | 776 | 0 | 0 | 1.90 / 129.17 |
| baidu-default | PASS | 100,000 | 100,000 | 0 | 0 | 0 | 0.49 / 5.25 |
| baidu-cached | PASS | 100,000 | 100,000 | 0 | 0 | 5 | 0.98 / 3.48 |

Each probe makes 100,000 attempts from four consumers, validates positive IDs and worker 1, sorts successful results for duplicate detection, and decodes timestamps using the matched format. Maximum future seconds is the largest positive difference between encoded epoch seconds and wall time observed immediately after return. These are small finite checks, not distributed/restart uniqueness proofs. A PASS probe means that its assertions passed; it does not mean zero rejected calls. The first observed errors are retained in `probe-*.txt` and `result.json`.

Latency columns time successful calls in this finite burst only, exclude failed-call latency, and include instrumentation perturbation. They are **NOT_COMPARABLE** as sustained latency/SLO results. A fixed offered-rate latency experiment was **NOT_RUN**. Smartscity can wait up to its existing 10 ms call budget; Baidu Default can busy-wait until the next second; Baidu Cached can reject immediately.

## Configuration and environment

| Item | Actual run |
|---|---|
| CPU | Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz |
| Logical CPUs / RAM | 12 / 16 GiB |
| OS | macOS-26.5.1-x86_64-i386-64bit-Mach-O |
| JVM | openjdk version "21.0.12.1" 2026-08-18 LTS |
| JMH | 1.37; same harness and classpath for all implementations |
| JVM options | `-Xms512m -Xmx512m -Duser.timezone=UTC` |
| Layouts (time/worker/sequence) | standard 30/18/15; high-throughput 30/16/17 |
| Epoch / worker | 2026-01-01T00:00:00Z / isolated local worker 1 |
| Cache capacity | standard 65,536; high-throughput 262,144; 50% refill threshold |
| Cache options | Baidu boostPower=1, no scheduled refill; Smartscity normal 10 ms wait and clock checks |
| Consumers | 1, 4, 8; one shared generator per trial |
| Repetitions | 2 independent JVM forks; 2 x 1 s warmup; 3 x 2 s measurement; GC profiler |
| Budget | 480 s measurement budget; 45 s per JMH invocation; retained partial evidence on failure |
| Isolation | Sequential scenarios with rotating implementation order; no CPU affinity or exclusive-host guarantee |
| Storage/network | No database, ZooKeeper or network call in measured generation path |

Matching capacity uses public settings and differs from both products' default buffer sizes. Baidu's unchanged producer creates a pool sized to twice the detected processors; Smartscity owns one producer. Matching consumer count therefore does not mean matching total thread/resource usage. Upstream uses an unbounded executor queue; this bounded run does not establish overload stability. The shared workstation may have other activity, including concurrent documentation/repository restructuring. No cross-machine extrapolation is justified.

## Source and artifact provenance

- Baidu: [`2fcbc13d2016fcfb7648a18296951f6942215255`](https://github.com/baidu/uid-generator/tree/2fcbc13d2016fcfb7648a18296951f6942215255); clean upstream checkout; generation sources compiled unchanged with JDK 21 targeting Java 8. Only the dependency closure needed by generation is compiled, not the original database/Spring integration or upstream tests.
- Smartscity: current dirty working tree based on `f86b74153cb42dee1918c77c0ac613e796b6b5f9`; its Git commit alone does not identify the measured source. See `sources-before.json`, `sources-after.json` and `environment.json` for exact contents and working-tree status. The benchmark JAR was freshly built with the pinned Maven wrapper and JDK 21 before measurement.
- Smartscity benchmark JAR SHA-256: `a7caf88b45c201bc8e00f756b5e4ac2eb3f88a64bdbdc90c311026a8ab8c56fa`.
- Combined source/JAR stability gate: **False** due to source changes; measured JAR SHA-256 remained unchanged. The six changed source paths are listed below. The bytecode comparison above provides a narrower follow-up check, without changing the recorded gate status.

  - `module/smartscity-id/src/main/java/com/smartscity/id/api/IdGenerator.java`
  - `module/smartscity-id/src/main/java/com/smartscity/id/core/BitsAllocator.java`
  - `module/smartscity-id/src/main/java/com/smartscity/id/core/CachedUniqueIdGenerator.java`
  - `module/smartscity-id/src/main/java/com/smartscity/id/core/GeneratorOptions.java`
  - `module/smartscity-id/src/main/java/com/smartscity/id/core/IdLayout.java`
  - `module/smartscity-id/src/main/java/com/smartscity/id/spi/WorkerLease.java`
- Runtime dependencies: Spring core/beans 4.2.5.RELEASE, Commons Lang 2.6, SLF4J API/NOP 1.7.7; isolated under benchmark `target/`, with download checksums and SHA-256 records. No production POM dependency was added. NOP replaces logging output, not ID generation.
- Baidu's original 28-bit time field with its 2016 default epoch is exhausted by the client date of this report. The fixture explicitly configures both implementations with the same 2026 epoch and layouts; it does not silently alter upstream source or claim default configuration works today.

## Upstream published report

The [pinned upstream README](https://github.com/baidu/uid-generator/blob/2fcbc13d2016fcfb7648a18296951f6942215255/README.md#experiment-for-throughput) describes approximately six million IDs/s and a four-core consumer experiment. For its 31/23/9-bit layout, it reports 6,462,726 / 6,377,958 / 6,490,969 at 1 / 4 / 8 consumers. Those are historical published numbers, **NOT_COMPARABLE** to this run: the hardware description, exact JVM/options, warmup, timing method, repeat uncertainty and allocation data do not establish a matched environment. This experiment does not reproduce that historical configuration or validate the headline as a current guarantee.

Source inspection explains the semantic gap: [Baidu BufferPaddingExecutor](https://github.com/baidu/uid-generator/blob/2fcbc13d2016fcfb7648a18296951f6942215255/src/main/java/com/baidu/fsg/uid/buffer/BufferPaddingExecutor.java) increments a logical second for refill, while [Baidu DefaultUidGenerator](https://github.com/baidu/uid-generator/blob/2fcbc13d2016fcfb7648a18296951f6942215255/src/main/java/com/baidu/fsg/uid/impl/DefaultUidGenerator.java) waits on wall-clock seconds. Smartscity's generation cursor and monotonic-clock checks remain unchanged.

## Validation and limits

- **PASS**: local Maven benchmark/domain build; 39 executed domain tests passed, 3 opt-in restart tests skipped. Build log: `build.txt`. This is not a whole-reactor test run.
- **PASS**: unchanged upstream generation sources and new harness compiled; JMH discovered the comparison. See `compile-upstream.txt`, `compile-harness.txt`, `discovery.txt`.
- Whole-repository artifact boundary check: **FAIL** at the first invocation because another active repository redesign moved `module/smartscity-id-spring-boot` while the checker still referenced it. The SDK JAR boundary passed before that failure. See `artifact-boundaries.txt`. This comparison does not modify or repair that unrelated redesign and does not claim whole-workspace verification.
- **NOT_RUN**: upstream database/Spring integration suite, cross-process registration, restart/clock faults, fixed offered-rate latency, long soak and production deployment. Existing historical integration results are not rerun evidence.
- No production algorithm, public API, epoch default or POM dependency change was made for this task. No commit, tag, push, remote workflow or publication was performed.

## Reproduction and raw evidence

Use the [fixture guide](../../smartscity-id-benchmark/comparison/README.md). `result.json` contains summary metrics and independent probe status; each scenario's `.json` and `.txt` preserve JMH samples, commands and logs. `environment.json` records JVM, hardware, dependency/source/class fingerprints. The preflight directory records discovery without measurements. The first sandboxed preparation could not read CPU metadata; it was retried with the required local process permissions before any measured scenario.

## Correction after evidence reconciliation

The `ids`, `timeouts`, and `rejections` values in this initial fixture are JMH auxiliary iteration counters, which also count synchronization catch-up calls outside the primary timed loop. They are **not exact timed-loop successful-throughput measurements**. Their sum exceeds primary attempts/s by up to 13.824% (Baidu Default, standard, 8 consumers). The raw tables above are retained as exploratory auxiliary rates; multi-thread success-rate rankings and B/return estimates must not be treated as precise measurements. Single-thread counter totals matched primary throughput in these samples. [JMH 1.37 generated-loop source](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/generators/core/BenchmarkGenerator.java) includes calls during warmup/warmdown synchronization and reports primary measuredOps separately.

The user subsequently clarified that future-second generation is permitted and requested an optimization/retest. The no-future policy described above is the measured historical implementation, not a user-imposed requirement. Current behavior is being revised under ADR 0011; this report does not justify retaining that ceiling.
