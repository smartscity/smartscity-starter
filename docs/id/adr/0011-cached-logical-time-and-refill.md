# ADR 0011: Cached logical time and bounded refill

Status: ACCEPTED / IMPLEMENTED_UNRELEASED, 2026-10-25. Clock-validation ownership is defined by [ADR-0012](0012-producer-clock-validation.md).

The cached producer owns the logical second/sequence cursor. It advances to the next encodable second when its sequence is exhausted, or to validated wall time when that overtakes it. Neither field wraps. The public low-level `DefaultUniqueIdGenerator.next(second)` retains its explicit-second, zero-on-exhaustion contract.

Refill validates time and lease between bounded 256-ID chunks. The existing cursor encodes contiguous ranges within one logical second; only the prefix accepted by the ring advances its sequence. Full buffers consume no IDs, and partial publication retains the unaccepted suffix. Each range starts with checked encoding, excludes reserved zero and cannot cross sequence or timestamp bounds.

The primitive ring publishes payload ranges with one release of the tail. A consumer acquires the tail and must successfully CAS the head before reading a payload. Consumer-written reclamation generations prevent the producer from overwriting a delayed claimant's slot. Publication ordering is payload write, tail release/acquire, successful claim, payload read, reclamation release/acquire. Head distance alone never authorizes overwrite. Observation rechecks the head after loading a payload and never claims a slot. Positions fail before signed overflow.

The generator separates empty-cache and contended claims. Consumers use bounded retries, the original total wait budget, interruption checks and short parks. Claimed IDs rejected by post-claim admission are discarded. A coalesced producer notification is armed before rechecking occupancy and slot reclamation, preventing a missed refill wakeup. Suspended ownership and unreclaimed slots park instead of spinning. A paused claimant can delay reuse, so this is not a strict lock-free progress guarantee.

| Concern | Owner / decision |
|---|---|
| Logical second, sequence and encoding bounds | Existing single-producer cursor; no separate policy hierarchy |
| Payload visibility, claims and reuse | Existing bounded ring; no boxed full-second list or executor queue |
| Wakeups, waiting and shutdown | Existing generator lifecycle; one coalesced notification and bounded waits |
| Metrics | Fixed success/failure duration histograms; exact completed-call accounting and approximate concurrent snapshots |

The successful histogram also supplies generated counts, avoiding a duplicate success-counter update. Failed-call durations still contribute to overall latency. There is no sampling, unbounded per-thread registry or new public API.

Encoded timestamps are logical allocation time and may lead wall time. Sustained high rates consume the timestamp range faster than wall time; buffer size bounds memory, not cumulative lead. Use separate event timestamps for ordering and TTLs. Worker IDs remain non-reusable across restarts. No generation-path database query, timestamp persistence or worker-reuse protocol is introduced. Format, epoch defaults, configuration and coordinates are unchanged; see [migration](../migration.md) for the semantic changes.
