# ADR 0011: Cached logical time and bounded refill

Status: ACCEPTED / IMPLEMENTED_UNRELEASED, 2026-10-25. Authority: the user requests optimization against the Longfor/Baidu lineage and explicitly corrects the previously imposed prohibition on generating IDs for future seconds.

Cached generation may consume future logical seconds. The producer owns the logical second/sequence cursor; when a sequence is exhausted it advances to the next encodable second, and when wall time overtakes it it advances to validated wall time. Neither seconds nor sequences wrap. This restores the intended cached UID model. The earlier no-future rule was not the user's requirement and is superseded here; it must not be cited as a reason to retain a wall-second throughput ceiling.

The existing public low-level `DefaultUniqueIdGenerator.next(second)` remains an explicit-second cursor. A package-private buffered path owns logical advancement. Cached generation continues to use a fixed-size primitive ring, one producer and no task queue. Refill work validates time/lease between bounded chunks. Consumers retain pre/post-claim validation, interruption, total wait budget and burn-on-failure. Clock drift checks remain tied to the startup anchor, not the borrowed logical timestamp. Idle producers still validate periodically. Shutdown remains bounded.

Coalesce wakeups with a per-generator atomic parked flag, owned with the producer lifecycle. Signal only an armed producer at low occupancy; recheck occupancy after arming to avoid a lost wakeup. The periodic check also covers a stalled ring claimant. Retry CAS contention a small bounded number of times before treating it as an unavailable claim; never add an unbounded consumer spin. Avoid unchanged volatile clock-deviation writes that contend across readers. Metrics remain exact counters/approximate snapshots as before, with no sampling or feature disablement.

| Problem | Pattern decision | Reason |
|---|---|---|
| Logical second and sequence | Reuse the existing single-owner cursor | Keep encoding bounds and advancement in one owner |
| Redundant refill signals | Coalesced notification in the existing lifecycle owner | One producer, one permit, no executor or queue |
| Repeated per-ID clock work | Bounded refill chunks | Amortize producer validation while consumers retain their checks |
| Contended claims | Bounded CAS retry | Avoid sleeping solely because another consumer won a single race |

Encoded timestamps are logical allocation time and can exceed request/event wall time. At rates above the sequence capacity, timestamp lead grows and the time-bit lifetime is consumed faster than wall time. Buffer size bounds memory, not cumulative lead. Never use decoded timestamps as event timestamps or TTL clocks. Worker IDs remain non-reusable across restarts; a fresh worker allows local logical state to restart without overlapping a prior worker's namespace. No database generation-path query, timestamp persistence or worker-reuse protocol is introduced.

Format, epoch defaults, configuration names and coordinates are unchanged. Migration of existing applications is a semantic review of decoded timestamp use, not an implicit format/schema migration. Benchmark before/after artifacts must be frozen outside Maven target directories so another build cannot replace them. Separate successful calls, failed calls, timestamp lead and allocation; do not mistake JMH auxiliary iteration counters for exact timed-loop throughput.

Verification: [controlled comparison and domain evidence](../../../test/evidence/uid-optimization-20261025/REPORT.md). 44 distinct domain tests and artifact boundary checks passed; database/distributed acceptance and production deployment were not rerun.

## Consumer hot-path follow-up

The subsequent user request authorizes code optimization and correctness verification only; benchmarks remain NOT_RUN for this follow-up. Reuse the generator's immutable layout to precompute inclusive millisecond admission bounds. Consumers validate the original clock samples directly against these bounds; only the producer converts validated milliseconds to relative seconds. Include the entire final second, saturating its millisecond endpoint at `Long.MAX_VALUE`. Divide before subtracting the epoch so valid layouts spanning the signed millisecond range do not overflow. Pre/post-claim sampling, terminal failure retention and burn-on-failure are unchanged.

`GeneratorMetrics` owns separate fixed 64-bucket successful and unsuccessful duration histograms. The successful histogram also owns the generated count, removing the duplicate success counter update. Both outcomes still contribute every duration to total latency and percentiles. Snapshots sum the buckets; concurrent snapshots remain approximate and quiescent counts remain exact. This trades 63 additional `LongAdder` objects and additional bounded snapshot reads for one fewer successful-call counter update. No per-thread registry, sampling policy, asynchronous reporting queue or new public API is introduced.

See the [hot-path correctness report](../../../test/evidence/uid-hotpath-20261025/REPORT.md) for the tested source snapshot, evidence and remaining measurement limits. The earlier benchmark results describe the earlier implementation, not this follow-up.
