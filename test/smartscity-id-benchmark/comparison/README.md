# Baidu UID Generator comparison

This opt-in local fixture compares the unchanged Smartscity `CachedUniqueIdGenerator` with Baidu `DefaultUidGenerator` and `CachedUidGenerator`. It is outside normal Maven source roots and adds no dependency to production or the ordinary benchmark JAR. Generated reports display Smartscity results only, while raw measurements retain all implementations. See the [retained benchmark](../../../docs/id/benchmark.md) for the latest curated snapshot.

## Reproduce

Use JDK 21 and the repository's pinned Maven wrapper. Select a fresh output directory. The runner downloads five pinned public Maven Central JARs into the ignored benchmark target directory, verifies download checksums, compiles only the unchanged upstream generation classes, and records SHA-256 fingerprints. Database/Spring application assembly is intentionally excluded. A local SLF4J NOP binding disables upstream logging; the generation implementation and its exception handling remain unchanged.

```sh
./mvnw -Pbenchmarks -pl :smartscity-id-benchmark -am package
git clone https://github.com/baidu/uid-generator.git /tmp/baidu-uid-comparison
git -C /tmp/baidu-uid-comparison checkout --detach 2fcbc13d2016fcfb7648a18296951f6942215255
python3 test/smartscity-id-benchmark/comparison/run.py \
  --upstream /tmp/baidu-uid-comparison \
  --output test/evidence/baidu-comparison-new --run
```

Without `--run`, the script compiles and checks discovery only. No measurement runs in a normal build. Network access is needed for initial dependencies; JMH uses loopback sockets to coordinate forked JVMs. The JMH annotation processor is the repository's pinned 1.37 artifact in the default Maven cache. Concurrent edits/builds must be avoided; the runner compares source/JAR hashes at completion and marks drift `NOT_COMPARABLE`. Never replace existing evidence. The temporary checkout and target compilation are not production installations.

## Measurement contract

- Three implementations, two layouts, 1/4/8 consumers: 18 throughput scenarios. Two forks, 2 x 1 s warmup, 3 x 2 s measurement, 512 MiB heap, UTC timezone, JMH GC profiler. Order rotates between profile/thread groups. Eight-minute measurement budget; an individual JMH invocation is limited to 45 seconds. Failure/timeout retains partial results and terminates owned process groups.
- Layouts are 30/18/15 and 30/16/17 timestamp/worker/sequence bits, epoch 2026-01-01 UTC, local fixture worker 1. Cache size is `2^(sequenceBits+1)`: 65,536 and 262,144, with 50% refill threshold. Baidu uses public `boostPower=1`; scheduled filling is disabled. Smartscity retains its 10 ms total call timeout and normal clock checks. These are matched-format experiments, not an out-of-box default-configuration comparison.
- The benchmark-scoped generator owns initialization and teardown. Consumer counters are thread-local. Existing builders/public setters act as composition roots; no production facade, strategy, clock override or algorithm patch is introduced. The fixed worker fixture is valid only for isolated process-local measurements, not distributed uniqueness.
- Report **successful IDs/s**, timeout/rejection rates, allocation MiB/s, B/attempt and estimated B/success. Upstream wraps buffer-empty and internal errors in the same `UidGenerateException`; its rejection count is not a claim that every error was benign buffer exhaustion. No hidden retry is used. Primary JMH operations are attempts, not necessarily IDs.
- Current Smartscity Cached and Baidu Cached both advance logical future seconds. The historical Smartscity baseline did not. Baidu Default busy-waits for the next wall second. Matching bits does not equalize clock/lease validation, producer count or queue policy.
- Three separate probes each make 100,000 attempts from four threads, sort successful IDs to check duplicates, validate worker/positive encoding, and record maximum observed timestamp lead. Their successful-call p50/p99 are small-burst diagnostics, exclude failed-call latency, and are not sustained latency/SLO evidence. No host clock changes are made. Probe failures are recorded independently from JMH completion.

The report must retain short-run uncertainty, machine/JVM details and actual errors. Neither an upstream headline nor this microbenchmark validates distributed allocation, restart safety, overload stability or production capacity. Full database/ZooKeeper integration and sustained tests remain outside this comparison.

## Controlled optimization experiment

`run-optimization.py --run --upstream <pinned-checkout> --variants <frozen-directory> --output <fresh-evidence>` expects `before/sdk.jar`, `before/benchmarks.jar`, `future-only/sdk.jar`, and `optimized/sdk.jar`. Build these with JDK 21. The future-only variant adds only logical-second advancement to the before source; the optimized variant also changes refill validation, notification coalescing, contention retry and unchanged deviation publication. Keep exact hashes and source snapshots with each run. Keep these artifacts outside ordinary Maven target directories.

The matrix contains standard at 1/4/8 consumers for all four variants, and high-throughput at 1/4/8 for optimized/Baidu Cached: 18 scenarios, two forks, the same timing and eight-minute budget as above. Four separate probes follow. The harness uses JMH Control start/stop flags to exclude synchronization catch-up calls from outcome counters; the runner rejects a mismatch between outcomes and primary timed-loop operations. Java fixture and SDK JAR hashes must stay unchanged. Preparation can also use `run.py --work-directory <isolated-directory> --smartscity-jar <frozen-benchmark-jar>`; input JARs are copied to content-addressed files before compilation.
