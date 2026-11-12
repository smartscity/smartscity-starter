# Changelog

## Unreleased

### Independent observability components

- Split the combined implementation into a pure Java masking SDK and independent logging/metrics starter families.
- Preserve Java packages, configuration keys, span mapping, pipe wire format and bounded UDP lifecycle.
- Add immutable masking policy, independent classpath tests and all component assembly combinations.


- Add the observability starter family with independent correlation, masking, logging and metrics switches; parent/current span aliases, bounded HTTP capture, pipe logs, Prometheus and a single-consumer StatsD UDP sender. See observability usage and verification notes.

- Sample latency with default probability 1/1024 while preserving exact outcomes and call-entry deadlines. Expose sample count/interval and unknown quantiles; interval 1 restores full collection. See ID migration notes.

- Notify refill before post-claim failures, including expired claims; retain failure-only timing diagnostics and the original wait budget.

- Observe remaining worker capacity asynchronously, with freshness and failure status; expose logical future lead and remaining timestamp capacity.
- Notify bounded waiting consumers on publication and shutdown; distinguish empty, contention and post-claim deadline timeouts without changing request deadlines.

- Publish bounded ID ranges and retain explicit CAS claims with delayed slot reclamation.
- Validate clocks in the producer; cached consumers retain local lifecycle/lease/failure gates and exact metrics. See the migration notes for delayed clock-fault detection.
- Preserve bounded waits and coalesced refill wakeups under contention.
- Retain one Smartscity benchmark report; remove generated evidence and redundant verification summaries from version control.

- Group Boot-generation examples under the unpublished samples/id aggregator.
- Add a repository-owned IntelliJ Google Java Style XML and retain Maven formatting enforcement.
- Remove the redundant configuration/style ADR; keep its rules in the component contract and coding guide.

- Keep only the independent ID SDK under module/; place all Boot integration under the ID starter family.
- Consolidate ID documentation under docs/id with one complete configuration and a lifetime table.
- Expand public method contracts and configuration metadata descriptions; retain author jack.
- Bind the guide YAML with origin-aware property sources in both starter test suites.

- Configure immutable per-instance epochs and complete bit layouts while preserving legacy defaults.
- Replace modulo worker mapping with bounded transactional registrations that never reuse committed workers.
- Separate group format, registration identity, worker number and incarnation/token metadata; require explicit schema-v2 migration.
- Require explicit application DataSource opt-in; preserve unpooled URL connections and local-only generation.
- Add stable error codes and context, Google-style Javadoc with author jack, formatter and documentation gates.

- Organize the repository by build, domain, starter, test and sample responsibilities.
- Merge ID API/SPI/core artifacts while preserving public Java types and generation behavior.
- Group Boot 2.7/3 and Boot 4 integration into two combined starters under one unpublished family; use standard source trees with a generation parity check.
- Remove the empty testkit; rename the production BOM and starter coordinates.
- Add dependency convergence, compatibility CI, artifact checks and an opt-in Central publishing workflow.

See [migration](docs/id/migration.md), [test commands](docs/id/testing.md) and the [benchmark](docs/id/benchmark.md).

### Explicit generator names and Roaring storage encoding

- Add BufferedIdGenerator and synchronous SequenceIdGenerator over the existing validated sequence cursor. Retain deprecated CachedUniqueIdGenerator and DefaultUniqueIdGenerator compatibility entry points; starter defaults still issue standard IDs.
- Add IdEncoder, IdentityIdEncoder and versioned RoaringIdEncoder with a reversible 63-bit permutation; add a non-owning RoaringIdGenerator view. Keep the standard protocol and zero-dependency core unchanged.
- Document sparse-container overhead, storage-only selection, ownership, versioned Raft snapshots and migration. Add generation/encoding/storage/live-pipeline JMH groups, deterministic four-way Bitmap comparisons, retention models and charts.
- Java 21 local verification: 198 reported tests, 165 executed, 33 conditional skips, zero failures/errors. Performance results remain exploratory and host-limited; memory comparisons retain both improvements and regressions.
