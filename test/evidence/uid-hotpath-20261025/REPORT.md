# UID consumer hot-path optimization: correctness-only follow-up

Date: 2026-10-25. Scope: optimize the existing SDK and verify correctness. Benchmark and profiling execution: **NOT_RUN**, explicitly excluded by the user. No new throughput, latency or allocation result is claimed. No commit, push or publication was performed.

## Diagnosis and evidence limits

The previous [JFR summary](../uid-optimization-20261025/profile/hotspots.json) reports `CachedUniqueIdGenerator.checkAvailable` as the top frame in 111 of 150 standard-profile samples and 110 of 141 high-throughput-profile samples. These are historical sampled stacks, including inlined work; they do not attribute exact CPU percentages to individual clock reads, arithmetic, counters or ring operations.

The locally retained Baidu source at commit `2fcbc13d2016fcfb7648a18296951f6942215255` has `CachedUidGenerator.getUID()` delegate to `RingBuffer.take()`. Its consumer path does not perform the SDK's fresh clock/lease admission checks or duration accounting. A successful uncontended SDK call still performs two independent nano/wall/nano validation samples, two lifecycle/lease checks, ring ownership acquisition and duration accounting. The additional work is a concrete structural difference; it does not numerically explain the entire previous throughput gap.

Two avoidable operations were identified in that path:

| Work per successful, nonwaiting consumer call | Before | After |
|---|---|---|
| Milliseconds-to-relative-seconds conversion for admission | Twice | None; compare precomputed millisecond bounds |
| Success-path metric `LongAdder` updates | Three: generated, total duration, duration bucket | Two: total duration and successful duration bucket |
| Fresh clock samples | Two | Two |
| Failed/closed/expired ownership checks and terminal clock failure retention | Required | Preserved |

These counts follow the source; they are not a hardware instruction count or measured speedup. Lease-error construction was also moved out of the common admission method; no JIT inlining benefit is asserted.

## Implementation and ownership review

Only two existing production Java files changed: `CachedUniqueIdGenerator` and `GeneratorMetrics`. No ring, encoding cursor, clock sampling, worker registry or public API change was made. [Exact Java patch](implementation.patch) includes the two added test classes.

| Problem | Decision | Owner and invariant |
|---|---|---|
| Repeated time conversion | Reuse immutable `IdLayout`; cache two derived bounds | Generator lifetime matches the captured layout; both bounds are final |
| Repeated successful-call count update | Reuse the metrics owner and aggregate successful duration buckets | Every completed call records one duration; only successful buckets contribute to generated count |
| Potential observer framework or per-thread accumulator | Not introduced | Existing bounded counters suffice; no queue, thread registry or new lifecycle |

The millisecond upper bound includes the entire final encoded second and saturates at `Long.MAX_VALUE`. Producer conversion divides before subtracting the epoch, avoiding overflow for valid layouts spanning the signed millisecond range. Consumer pre/post-claim validation and burn-on-failure are unchanged. Future logical seconds remain permitted; logical exhaustion still fails without wrapping.

Metrics use two fixed sets of 64 duration buckets. This adds 63 `LongAdder` objects net and bounded snapshot work in exchange for removing one update per successful call. Both successful and unsuccessful durations still contribute to total latency and quantiles. Counts remain exact after writers complete; concurrent snapshots remain approximate. No metric sampling or disabling was introduced.

## Executed correctness and build checks

Build environment: Temurin JDK 21.0.12.1+1, pinned Maven wrapper 3.9.15, macOS arm64; see [environment](environment.json) and [runtime version](java-version.txt). All clocks used for fault injection are local test objects. The host clock was not changed.

| Check | Result | Evidence |
|---|---|---|
| SDK verification including opt-in bounded restart/ring/close correctness cases | **PASS: 50 tests, zero failures/errors/skips** | [Final build](domain-verify-final.txt), [suite counts](tests.json), [XML reports](surefire-reports) |
| Production reactor package, tests and test compilation excluded | **PASS** | [Build](production-package-final.txt) |
| Artifact dependencies, framework isolation, bytecode, metadata, imports and BOM | **PASS** | [Checker](artifact-boundaries-final.txt) |
| Public signatures in all 23 SDK classes | **PASS: unchanged** | [Comparison](api-comparison.json) |
| Spotless and dependency convergence | **PASS** | SDK and production build logs |
| Repository layout, documentation links and mirrored starter sources | **PASS** | [Repository checker](repository-check.txt) |
| Benchmark, new profiling, allocation measurements | **NOT_RUN** | Explicit user exclusion |
| Database/distributed and full starter test execution | **NOT_RUN** | Outside this core change's executed verification |

The six new tests cover 118 boundary samples across eight layouts, including negative epochs and extreme signed millisecond endpoints; two independent consumer clock samples; timestamp exhaustion after claiming an ID and terminal failure retention; successful versus failed call accounting; latency bucket boundaries; and exact counts after 2,048 concurrent mixed metric updates. Existing tests retain multi-consumer uniqueness, borrowed logical seconds, timestamp exhaustion, clock rollback after claim, unstable sampling, empty-cache timeout, interruption and close-race assertions. Existing tests were not weakened or edited.

The first SDK verification passed 49 tests. One additional post-claim capacity regression was then added, followed by the final 50-test run above. These are two snapshots of the same test suite, not 99 distinct tests.

The initial production command `./mvnw -o -DskipTests package` **FAILED** during Boot 2/3 test compilation: the existing `DocumentationConfigurationTest` calls Java 11 `Files.readString(Path)` while the module compiles for Java 9 ([failure log](production-package.txt)). That unrelated worktree file was preserved. The subsequent `-Dmaven.test.skip=true package` verifies production packaging only and does not clear or conceal that test-compilation failure. A full-reactor test pass is not claimed.

## Reproduction and source binding

With `JAVA_HOME` pointing to JDK 21, run these independently, without enabling the benchmarks profile:

```sh
./mvnw -o -pl :smartscity-id -am -Did.test.restart=true verify
./mvnw -o -Dmaven.test.skip=true package
python3 test/check-artifacts.py
```

[Before hashes](before-sources.json), [tested hashes](sources.json), [frozen tested sources](tested-sources.tar.gz) and the patch bind this result to the actual code. The archive hash is recorded in `environment.json`. The earlier comparison report remains evidence for its own snapshot only. Whether these changes reduce the single-thread gap, and by how much, remains **NOT_MEASURED**.
