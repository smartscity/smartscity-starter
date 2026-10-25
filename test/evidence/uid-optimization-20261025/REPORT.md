# Cached UID restoration and optimization against Baidu

2026-10-25 (Asia/Singapore). Status: **MEASURED**, 18/18 controlled JMH scenarios and 4/4 bounded probes completed in **368.85 s**. Frozen SDKs and Java harness remained unchanged. Current SDK source hashes still match the tested optimized snapshot. This report supersedes the initial no-future comparison for current implementation behavior.

## Outcome

The earlier wall-second throughput ceiling was an incorrectly imposed policy, not the user's requirement. Following the user's clarification, Cached generation again pre-generates future logical seconds. The standard format is no longer limited to 32,768 API calls per wall second. The encoding widths/default epoch and permanent worker non-reuse remain unchanged. See [ADR-0011](../../../docs/id/adr/0011-cached-logical-time-and-refill.md).

The optimized implementation delivered approximately 2.55 / 3.17 / 3.57 million successful IDs/s at 1 / 4 / 8 consumers in the standard matched-configuration experiment. Baidu Cached was still faster in those cases, particularly at one consumer. This work does **not** claim parity or a universal win. Compared with the controlled future-time-only variant, optimized point estimates improved 1.49x / 2.09x / 2.87x; those are short-run means with uncertainty below, not sustained capacity guarantees. Most of the improvement over the original 32k/s result comes from restoring the intended logical-time policy, not from low-level tuning.

## Standard format: successful IDs per second

| Consumers | Before (no future time) | Future time only | Optimized | Baidu Cached | Optimized / future-only |
|---|---:|---:|---:|---:|---:|
| 1 | 32,622 | 1,707,515 | 2,550,474 | 8,959,647 | 1.49x |
| 4 | 32,739 | 1,516,690 | 3,166,001 | 5,154,260 | 2.09x |
| 8 | 32,782 | 1,243,750 | 3,567,824 | 3,962,265 | 2.87x |

All four variants use 30/18/15 bits, epoch 2026-01-01 UTC, worker 1 and a 65,536-slot cache with 50% refill threshold. The matched cache is larger than Smartscity's unchanged default of 16,384 slots. It is not an out-of-box comparison. The before/future-only/optimized variants use identical production options; only their recorded source differs.

The future-only variant changes only the cache producer's logical-second advancement; it retains the old per-ID validation, wakeups and single-attempt claims. [future-only.patch](future-only.patch) makes this control auditable. [implementation.patch](implementation.patch) records the full SDK/test change against the before snapshot.

## High-throughput format: successful IDs per second

| Consumers | Optimized | Baidu Cached |
|---|---:|---:|
| 1 | 894,269 | 5,041,062 |
| 4 | 2,884,580 | 4,151,326 |
| 8 | 4,045,857 | 5,173,453 |

These cases use 30/16/17 bits and 262,144 slots for both implementations. The optimized single-thread mean was unusually low and unstable: its six measured iterations ranged from approximately 0.44 to 1.90 million IDs/s. That result is retained, not replaced with a better run. A separate JFR diagnostic later observed about 2.80 million IDs/s for the same high-throughput configuration; it had one fork, instrumentation and a different run time, so it is **NOT_COMPARABLE** as a replacement formal result. The data do not establish that this profile inherently causes the slowdown or that its lower mean is stable. More exclusive-host measurement would be required to attribute the variation.

## Detailed rates, errors and allocation

| Layout | Threads | Variant | IDs/s ± 99.9% error | Timeout/s | Upstream exception/s | Estimated B/success | Allocation MiB/s |
|---|---:|---|---:|---:|---:|---:|---:|
| standard | 1 | before | 32,622 ± 200 | 95.99 | 0.00 | 2.2664 | 0.070 |
| standard | 1 | future-only | 1,707,515 ± 655,461 | 0.00 | 0.00 | 0.0024 | 0.004 |
| standard | 1 | optimized | 2,550,474 ± 1,281,236 | 0.00 | 0.00 | 0.0017 | 0.004 |
| standard | 1 | baidu-cached | 8,959,647 ± 1,992,894 | 0.00 | 2.33 | 35.2713 | 299.945 |
| standard | 4 | future-only | 1,516,690 ± 298,012 | 2.66 | 0.00 | 0.0056 | 0.008 |
| standard | 4 | optimized | 3,166,001 ± 1,013,448 | 1.67 | 0.00 | 0.0024 | 0.007 |
| standard | 4 | baidu-cached | 5,154,260 ± 1,481,861 | 0.00 | 918.01 | 41.3199 | 201.117 |
| standard | 4 | before | 32,739 ± 197 | 383.48 | 0.00 | 8.7752 | 0.272 |
| standard | 8 | optimized | 3,567,824 ± 231,582 | 0.08 | 0.00 | 0.0025 | 0.008 |
| standard | 8 | baidu-cached | 3,962,265 ± 2,735,151 | 0.00 | 20748.47 | 116.5967 | 381.019 |
| standard | 8 | before | 32,782 ± 105 | 752.54 | 0.00 | 17.1326 | 0.530 |
| standard | 8 | future-only | 1,243,750 ± 361,132 | 19.69 | 0.00 | 0.0202 | 0.022 |
| high-throughput | 1 | optimized | 894,269 ± 1,498,304 | 0.00 | 0.00 | 0.0058 | 0.004 |
| high-throughput | 1 | baidu-cached | 5,041,062 ± 4,313,830 | 0.00 | 0.00 | 45.6095 | 219.795 |
| high-throughput | 4 | baidu-cached | 4,151,326 ± 1,037,130 | 0.00 | 0.00 | 54.5170 | 214.209 |
| high-throughput | 4 | optimized | 2,884,580 ± 521,375 | 0.33 | 0.00 | 0.0023 | 0.006 |
| high-throughput | 8 | optimized | 4,045,857 ± 386,617 | 2.41 | 0.00 | 0.0026 | 0.010 |
| high-throughput | 8 | baidu-cached | 5,173,453 ± 1,920,921 | 0.00 | 1224.83 | 54.3627 | 244.614 |

Rates are successful API returns, not attempts. The harness uses JMH `Control.startMeasurement` / `stopMeasurement` to exclude synchronization catch-up calls from its thread-local outcome counters. Every scenario satisfied `successes + timeouts + upstream exceptions = primary timed-loop attempts` within 0.001%; the actual discrepancies are recorded in [result.json](measurement/result.json). This corrects the auxiliary-counter timing defect found in the [initial report](../baidu-comparison-20261025/REPORT.md).

Smartscity counts only `TIMEOUT` as an expected failed attempt; other domain errors abort JMH. Baidu's `UidGenerateException` wraps both ordinary empty-buffer rejections and internal errors, so the upstream exception column cannot classify every failure as benign exhaustion. No hidden retry is used. Estimated B/success is JMH B/attempt multiplied by attempts/s divided by successes/s; process-wide observed allocation includes the producer. Near-zero values include background measurement noise and do not assert literally zero JVM allocation. Confidence half-widths are JMH's reported 99.9% errors across two forks x three measurement iterations.

## Bounded uniqueness and timestamp probes

| Variant | Attempts | Successes | Failures | Duplicates | Maximum future seconds | Success p50 / p99 (µs) |
|---|---:|---:|---:|---:|---:|---:|
| before | 100,000 | 99,446 | 554 | 0 | 0 | 1.802 / 102.726 |
| future-only | 100,000 | 100,000 | 0 | 0 | 3 | 1.522 / 125.219 |
| optimized | 100,000 | 100,000 | 0 | 0 | 3 | 1.909 / 10.182 |
| baidu-cached | 100,000 | 100,000 | 0 | 0 | 5 | 0.482 / 2.853 |

Each independent probe runs four consumers for 100,000 total attempts, checks positive IDs and the expected worker, sorts successful values to check duplicates, and observes decoded timestamp lead. All four probes passed their assertions. These are finite single-process checks; they do not prove distributed uniqueness. Probe latency is a short-burst diagnostic for successful calls only, excludes failures, and is not a sustained offered-rate latency/SLO result.

Logical timestamps may lead real time. At 3.57 million IDs/s with 32,768 sequences per logical second, consumption alone corresponds to roughly 109 logical seconds per wall second; timestamp lead can grow by roughly 108 seconds per wall second, before accounting for discarded/pre-filled IDs and timing variation. This is a format-based illustration, not a measured steady lead-growth rate. Fixed cache size bounds memory, not cumulative lead. Time-field exhaustion fails closed. Applications must store event time separately and must never reuse an allocated worker after shutdown. See [migration guidance](../../../docs/id/migration.md#cached-logical-time).

## Implementation and ownership

- `DefaultUniqueIdGenerator` retains its explicit-second public API and owns a package-private buffered path that advances logical seconds, catches up to validated wall time, skips reserved zero, and rejects timestamp exhaustion before wrap.
- `CachedUniqueIdGenerator` still owns one producer, its bounded primitive ring and cleanup. It validates the producer between 256-ID chunks instead of repeating clock validation twice per produced ID. Consumers retain pre/post-claim clock/lease checks, interruption, total wait budget and burn-on-failure. A per-instance parked flag coalesces refill notifications; arming/rechecking and a bounded periodic check cover wakeup races and stalled claimants. No task queue or extra executor is introduced.
- `RingBuffer` retries contention up to eight CAS attempts before the existing bounded wait path. A failed CAS never grants ownership; per-slot acquire/release generations and overflow protection remain intact.
- `MonotonicClock` avoids publishing an unchanged deviation value to the shared volatile field. Clock samples, tolerance, stable-sample retry bound and terminal-failure rules are preserved. Metrics are not disabled or sampled away.

Pattern review: reuse the single-owner cursor and existing lifecycle composition root; add coalesced notification within that owner, not a general scheduling abstraction. No production dependency, public Java signature, property name, schema, coordinate, epoch default or bit default changed. The timestamp semantics intentionally changed under the user's correction; this is documented separately from source-level API compatibility.

The retained [JFR hotspot summaries](profile/hotspots.json) show most sampled consumer stacks in `checkAvailable`, with producer samples in `fill`. These samples are a diagnostic lead, not an exact CPU-cost attribution or proof of one unique bottleneck. The remaining single-thread gap to Baidu is not resolved by removing correctness checks merely to improve the score.

## Machine and method

- CPU: Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz; 12 logical CPUs; 16 GiB RAM.
- OS: macOS-26.5.1-x86_64-i386-64bit-Mach-O.
- JVM: `openjdk version "21.0.12.1" 2026-08-18 LTS`; JMH 1.37; `-Xms512m -Xmx512m -Duser.timezone=UTC`.
- Each scenario: two independent JVM forks, 2 x 1 s warmup, 3 x 2 s measurement, GC profiler. One shared generator with 1/4/8 consumers; implementation order rotates between thread groups. Eight-minute matrix budget; actual 368.85 seconds including probes. Two additional single-fork JFR diagnostics are explicitly separate from the formal matrix.
- Same layouts/cache capacities for compared products. Baidu uses public boostPower=1, no scheduled filling, isolated worker assignment and NOP logging. Its fixed refill executor still uses twice the detected processors and an unbounded task queue; Smartscity retains one producer and no task queue. These are different resource policies.
- No database, ZooKeeper or network call on the measured generation path. Host was not CPU-affined or guaranteed exclusive; other workstation activity and thermal/runtime variation cannot be ruled out. No sustained-performance or production-capacity claim.

## Provenance and reproduction

Baidu's generation classes are unchanged from [commit `2fcbc13d2016fcfb7648a18296951f6942215255`](https://github.com/baidu/uid-generator/tree/2fcbc13d2016fcfb7648a18296951f6942215255). Its generation dependency closure is compiled with JDK 21 targeting Java 8; original integration code/tests are not part of this local benchmark. Runtime versions and SHA-256 fingerprints are in [environment.json](measurement/environment.json) and [preparation evidence](measurement/prepare/environment.json). No upstream dependency enters a Smartscity production artifact.

- `before` SDK SHA-256: `61cf9a854ef056cde88df27ad9f11bd056ac9c75b9062c1980b7f9a078f841b4`.
- `future-only` SDK SHA-256: `0b979785cbaca96de9279ea9c685bc17ba921e8335429e0bd045fcf868feab94`.
- `optimized` SDK SHA-256: `47aa8d5b14178c3c599a31c82a894eafcd7665ba1018442c71d5df049ef2e5ff`.

The three source snapshots and exact benchmark Java/Python files are archived in [frozen-sources.tar.gz](frozen-sources.tar.gz); its checksum and scope are in [source-archive.json](source-archive.json). The archive contains no upstream source, credentials or external service configuration. Build the before benchmark JAR with the pinned Maven wrapper/JDK 21, compile/package each snapshot SDK for Java 9, and use the fixture's `run-optimization.py` with a frozen directory containing `before/sdk.jar`, `before/benchmarks.jar`, `future-only/sdk.jar` and `optimized/sdk.jar`. The extra SDK JAR precedes the SDK copy inside the common benchmark JAR on the runtime classpath. Exact command lines are retained per scenario. See the [fixture guide](../../smartscity-id-benchmark/comparison/README.md).

The original Longfor temporary checkout/ZIP is no longer present in the inspected locations. Its prior source audit and hash manifest remain in repository history; this task used that recorded design lineage and freshly inspected pinned Baidu source, not a newly executed Longfor binary. No Longfor performance figure is invented.

## Verification

- **PASS**: 41 regular domain tests, plus the three separately enabled bounded ring/close regression tests: 44 distinct tests passed. The latter include tiny rings, yielding consumers and 50 concurrent close races with one lease close each. See [domain build](optimized-build-final.txt) and [additional regressions](close-ring-regressions.txt).
- New/updated assertions cover logical-second rollover at a frozen wall clock, reserved zero, forward time catch-up, time-bit exhaustion without wrap, concurrent uniqueness across many logical seconds, paused-producer timeout, rollback, failure after claim and closure. Empty-cache tests now pause the producer explicitly rather than depending on the removed no-future ceiling. Existing fault/ownership assertions were retained.
- **PASS**: public signatures for all 23 SDK classes match the before artifact; [API comparison](api-comparison.json). This is not a claim of unchanged timestamp semantics.
- **PASS**: production packaging with tests explicitly skipped and dependency convergence/format gates; [package log](production-package.txt). **PASS**: current artifact checker covering SDK isolation, production BOM, generation separation, registration and metadata; [boundary log](artifact-boundaries.txt). Starter runtime/integration tests were not executed in this optimization task.
- **PASS**: 18 complete JMH records, 108 measurement samples, four uniqueness probes, outcome reconciliation and frozen artifact/harness hashes. No benchmark ran during normal Maven builds.
- **NOT_RUN**: new real database/ZooKeeper or cross-machine acceptance, production deployment, long soak, fixed offered-rate latency. Existing historical results do not validate those boundaries for this run.
- No commit, tag, push, remote workflow or publication was performed. Unrelated repository redesign changes were retained.
