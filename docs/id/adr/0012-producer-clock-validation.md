# ADR 0012: Producer-side clock validation for cached issuance

Status: ACCEPTED / IMPLEMENTED_UNRELEASED, 2026-10-25.

Adopt the downloaded Longhub cached consumer model: read an already encoded ID from the ring without sampling wall/monotonic time on every successful request. The existing producer validates its startup anchor, clock deviation and layout time bounds before each bounded refill, and periodically while idle. It publishes terminal failure through the existing atomic failure reference. Consumer admission reads local lease, closed and published failure state before and after the claim. No new validator thread, task queue, time cache, configuration switch or public API is required. The existing single-producer lifecycle remains the owner of clock validation, logical time, refill and shutdown.

This explicitly supersedes ADR-0011's two independent clock samples per consumer call. An undetected clock fault does not synchronously reject a cached ID. Detection occurs on the producer's next scheduled validation; its idle park request is 1 ms, not a real-time detection bound. A descheduled or blocked producer delays detection, and a transient fault entirely between validations may not be detected. Cached IDs may be consumed during this interval. Once a failure is published it is terminal, including after wall-clock recovery. Encoded time is logical allocation time, never an event timestamp. No worker is reused, no logical sequence/timestamp wraps, and every newly produced range still validates its encoding bounds.

Retain exact success/failure and latency accounting because it is an existing observation contract. A normal call still takes two `System.nanoTime` readings for latency, but no `TimeSource` reads or drift arithmetic. Move unavailable-claim retries, wait timing, parks and timeout checks to a cold method; the initial attempt and all waiting share the original call-start deadline. There is no sampled or disabled metric disguised as a zero value. Lease suspension/expiry and closure retain local pre/post-claim rejection; this is not a byte-for-byte drop-in of Longhub's public implementation.

Do not copy Longhub's stale-cursor `updateAndGet` ownership test. If two consumers read cursor C and one claims the last item C+1, the other's update can return C+1 without changing it; comparing that value with its stale C falsely authorizes a second read. Retain explicit successful CAS ownership and slot reclamation. Also retain the bounded/coalesced producer rather than copying an unbounded task queue or boxed full-second list. Existing framework-independent API, worker registry, format and startup database calibration stay in place.

| Concern | Pattern / owner | Decision |
|---|---|---|
| Clock/encoding validation | Existing producer owns the cursor and clock | Validate before generation and during idle periods; publish terminal state |
| Consumer admission | Existing generator lifecycle | Read local gates only, before and after claiming |
| Empty/contended queue | Cold bounded wait path | Preserve total deadline, cancellation, wait metrics and burn-on-failure |
| Visibility and uniqueness | Existing ring ownership protocol | Explicit CAS claim and delayed slot reclamation; no copying of ambiguous claims |

Update deterministic clock tests to wait for producer detection instead of asserting synchronous per-call sampling. Add explicit coverage of cached issuance before detection and zero consumer `TimeSource` reads; retain published-failure rejection, burn-after-claim, idle full-cache detection, unstable sampling, clock recovery non-resumption, initialization calibration and encoding bounds.
