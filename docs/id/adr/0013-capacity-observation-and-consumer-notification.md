# ADR 0013: Capacity observation and consumer notification

Status: ACCEPTED / IMPLEMENTED_UNRELEASED, 2026-10-27.

Expose worker capacity, logical timestamp lead, remaining logical seconds and unavailable-claim diagnostics without changing encoding, allocation, clock-failure policy or request deadlines. Monitoring is observational and cannot authorize issuance or worker reuse.

`RegistryCapacity` is an immutable API value containing slots, remaining slots, the observation-start timestamp and UNKNOWN/REGISTERED/OBSERVED/FAILED status. `WorkerLease.capacity()` defaults to UNKNOWN for existing standalone implementations. The JDBC registration receipt supplies an initial upper bound; a read-only SELECT observes the committed group cursor without allocation locks or updates. Other registrations can reduce capacity immediately after observation. Failure retains the last values and timestamp; unknown capacity is not zero.

The starter's `WorkerAllocation` owns a demand-driven `RegistryCapacityMonitor`. Snapshot reads request at most one asynchronous JDBC read per 30 seconds, with no queue and no concurrent reads. No snapshot or issuance call waits on JDBC. A stalled driver suppresses subsequent reads instead of accumulating threads. Close interrupts the sampler and prevents later publication; the executing reader owns eventual connection cleanup. Application DataSources remain application-owned. This is not a heartbeat or ownership renewal. Alert on sample age and status as well as remaining capacity.

The producer publishes its logical-second watermark when a published range advances it. Local snapshots calculate lead against current wall time and remaining whole logical seconds from that watermark. Sampling does not advance time or validate the clock. Lead is allocation time, not event time; remaining seconds exclude the partially used current second and do not imply wall-clock service life. Counters distinguish empty observations, exhausted CAS retries, parks and timeout stages. These describe observed states, not proof of a scheduling or CPU root cause.

Cold-path consumers register in a fixed 64-slot one-shot notification set, recheck admission and the ring, then park. Refill publication, terminal producer failure and close notify registered consumers. A notification before parking leaves a permit; a publication before registration is covered by the recheck. Overflow uses the existing bounded timed polling. Registration and cancellation cannot claim or release an ID slot. Keep the original total deadline, exact outcome counters and burn-on-failure. Retry counts accumulate locally and publish when a wait completes; in-flight retries are not yet reflected in snapshots. Notifications reduce unnecessary polling delay, not OS scheduling delays, and do not guarantee zero timeouts or fairness.

| Concern | Pattern / owner | Decision |
|---|---|---|
| Registry observation semantics | Immutable value and existing lease port | Additive API; preserve external allocator compatibility |
| JDBC observation lifecycle | Starter-owned asynchronous sampler | One in-flight operation; no heartbeat, pool or domain dependency |
| Logical allocation watermark | Existing producer cursor | Publish on logical-second changes, no additional successful-consumer work |
| Waiting consumer notifications | Bounded notification owner | Register/recheck/signal protocol, no unbounded queue or executor |

Verification covers notification-before-park, bounded registration, cancellation, concurrent payload ownership, capacity across independent registrations, stale/error observations, non-cooperative reader shutdown, logical lead and encoding boundaries. Compare benchmark success rates, timeout stages and allocation using frozen sources; short-run throughput alone is not a sustained-latency guarantee.

A consumer requests refill immediately after obtaining a positive claim and before post-claim admission/deadline checks. Slot release has already happened, so even a burned ID must notify an armed producer. Moving notification out of the success-only return path prevents rejected or expired claims from suppressing that wakeup. Failure-only timeout details retain elapsed time, time before entering the wait path, park count/duration and approximate occupancy; this text is diagnostic, not a machine-readable API.
