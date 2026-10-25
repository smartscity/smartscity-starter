# Historical registry, lifecycle and Javadoc verification

Historical snapshot only; this report predates the starter-family revision and does not validate it.

Date: 2026-10-25. Snapshot evidence is in [registry-20261025](../../test/evidence/registry-20261025). [Source fingerprints](../../test/evidence/registry-20261025/sources.sha256) identify this delivered state. Historical configuration/style and repository-redesign results remain unchanged in their evidence directories and are not substituted for the executions below. Build: macOS 26.5.1 x86_64, Temurin 21.0.12.1, pinned Maven 3.9.15.

## Findings and implementation

The original Longfor/Longhub temporary sources and archive are no longer available. The historical static audit at `f86b741:docs/proposals/id-v1/legacy-input-audit.md` and the retained 48-file hash manifest record modulo worker encoding, non-atomic occupancy checks, future-second borrowing and lifecycle/consumer-claim risks. Those are historical source observations, not fresh legacy execution or measured production incidents. The current pre-change worktree independently confirmed raw-ID modulo allocation and eager application DataSource resolution. [ADR-0009](adr/0009-worker-registration.md) separates those findings from the new decisions.

The group now owns immutable format/version and a locked monotonic worker cursor. A committed worker registration has separate row ID, encoded worker number, instance UUID, ownership token, diagnostic metadata and UTC event timestamps. SQL uniqueness plus one transaction prevents concurrent duplicate allocations. Committed numbers are never reused, including after failed startup, stopped processes, stale records and unknown commits. Exhaustion is explicit. No status update, token or ZooKeeper node release authorizes recycling.

JDBC remains confined to startup/calibration/activation and shutdown. Dedicated URLs use unpooled connections; application DataSources require explicit opt-in and remain application-owned. Borrowed resources close at operation boundaries. Generation, cached readiness checks and metrics use local state. Error Reason names remain stable and now carry machine codes, default messages, optional context and the original cause.

All 53 maintained top-level Java types have meaningful Javadoc with `@author jack`. Nontrivial format, clock, registration and lifecycle APIs document their inputs, results, failures or ownership. Deprecated EPOCH has a summary and migration guidance. Google Java Style governs formatting and Javadoc structure; the author convention is project-specific. Obvious accessors may omit repetitive documentation. Pinned Spotless/google-java-format checks all source sets; release Javadoc enables author rendering and doclint for syntax, HTML and references. No new Maven module or generic registry framework was added.

See [adapter design](README.md), [configuration/errors](USER_GUIDE.md) and [migration](migration.md#registry-schema-v2).

## Executed gates

| Gate | Result | Evidence / scope |
|---|---|---|
| Final clean install, 12 reactor projects | ✅ PASS | [Build](../../test/evidence/registry-20261025/reactor-final.txt), [counts](../../test/evidence/registry-20261025/reactor-reports/totals.json): 138 reported, 105 executed, 33 explicitly gated skips; zero failures/errors. Includes both adapters, samples, benchmark packaging, formatter and dependency convergence. |
| Registry and resource fault assertions | ✅ PASS | Shared JdbcIntegrationTest covers concurrent creation, exact bounds, no reuse, stale registrations, duplicate incarnation, token rejection, NULL epoch, rollback and unknown commit. AutoConfigurationTest verifies explicit DataSource resolution and zero retained connections after startup/shutdown; MySqlIntegrationTest verifies no generation-path JDBC. |
| MySQL 9.6, final source | ✅ PASS | [Both adapters](../../test/evidence/registry-20261025/mysql-final/result.json): six real integration tests each, zero skips, including shared bounded-registry/metadata assertions, process lifecycle and local-only generation. Private database cleaned up. |
| PostgreSQL 14.15 | ✅ PASS | [Both adapters](../../test/evidence/registry-20261025/postgres/result.json): one integration scenario each, with shared concurrent bounded-registry/metadata assertions, clock/group conflicts and shutdown. Zero skips; private database cleaned up. |
| Bounded restart, ZooKeeper and database outage | ✅ PASS | [Result](../../test/evidence/registry-20261025/faults/result.json): 71 selected tests plus one outage test, zero skips. Includes 1000 restarts, 20 process replacements, session expiry, denied startup, cancellation and close races. Private services cleaned up. |
| Installed starter compatibility | ✅ PASS | [Matrix](../../test/evidence/registry-20261025/compatibility/result.json): seven Boot versions on JDK 17 and 21, eight checks each, 112 executions without skips/failures/errors. Both packaged samples also executed against the private MySQL instance. Cleanup verified. |
| Standalone runtime | ✅ PASS | [Probe](../../test/evidence/registry-20261025/runtime.txt): same domain JAR, five layouts and 320 generated/decoded IDs on each of JDK 17 and 21. |
| Javadoc, authors and style | PASS with upstream warnings | [Javadoc](../../test/evidence/registry-20261025/javadoc.txt), [author/render check](../../test/evidence/registry-20261025/javadoc-result.json): three production Java modules generated; BitsAllocator HTML displays jack. One optional upstream Jackson annotation warning per adapter remains; no doclint error. |
| Artifact boundaries and metadata | ✅ PASS | [Checks](../../test/evidence/registry-20261025/artifacts.txt): zero domain production dependencies, separate bytecode/framework generations, code-free starters, production-only BOM and generated configuration metadata including instance fields/DataSource opt-in. |
| Behavior/API comparison | PASS within stated scope | [Disassembly](../../test/evidence/registry-20261025/bytecode-comparison.json): 27 domain/harness classes have unchanged normalized instructions/signatures; three changed classes contain the error model or contextual error strings. [Existing public signatures](../../test/evidence/registry-20261025/public-api.json) remain in those domain classes. JDBC adapter APIs intentionally change per migration notes. |
| Final tested artifacts | ✅ PASS | [Hashes](../../test/evidence/registry-20261025/tested-artifacts.json): final production JARs equal the installed JARs used by compatibility consumers. |
| Repository, links and CI syntax | ✅ PASS | [Repository check](../../test/evidence/registry-20261025/repository-check.txt), [CI check](../../test/evidence/registry-20261025/ci-check.txt), and whitespace check. No remote workflow executed. |

Counts overlap across gates and are not distinct test cases. Full destructive/restart coverage runs on the Boot 2.7/3 adapter; Boot 4 runs shared normal tests and real database/process integration, not a second full 1000-restart suite. The final MySQL gate includes the later metadata assertions; the earlier fault run remains recorded at its original execution. Production executable behavior was unchanged by the intervening Javadoc work.

Boot versions: 2.7.18, 3.3.6, 3.3.13, 3.4.13, 3.5.16, 4.0.9 and 4.1.2. Runtime JDKs: Temurin 17.0.0 and 21.0.12.1. ZooKeeper: 3.9.4. Initial failed reactor output is retained: the added DataSource test exposed H2's 32-bit MySQL time-function emulation; its test fixture now supplies a local millisecond time result. Real MySQL/PostgreSQL clock validation remains unchanged and separately passed.

## Bounded performance measurement

[Raw measurements](../../test/evidence/registry-20261025/benchmark-comparison.json) and [environment/provenance](../../test/evidence/registry-20261025/benchmark-environment.json): Intel i7-9750H, 12 logical CPUs, 16 GiB RAM, JDK 21, 512 MiB heap, standard layout, 1/4 consumers, two forks, 2 x 1 s warmup and 3 x 1 s measurement, GC profiling. The baseline is the pre-edit benchmark artifact from the preceding verified configuration/style snapshot; this task executed its before measurement. Benchmark execution logic is unchanged. No sustained run or tuning loop was performed.

| Threads | Before successful IDs/s | After successful IDs/s | Before bytes/attempt | After bytes/attempt |
|---|---:|---:|---:|---:|
| 1 | 32,489 +/- 214 | 32,497 +/- 163 | 2.403 +/- 0.072 | 2.388 +/- 0.056 |
| 4 | 32,775 +/- 128 | 36,201 +/- 14,947 | 8.990 +/- 0.184 | 8.189 +/- 3.179 |

Errors are JMH confidence-interval half-widths. No material successful-throughput regression was observed in this bounded standard-layout sample. The four-thread after measurement is noisy; improvement and statistical equivalence are NOT_ESTABLISHED. One-second sequence capacity, warm-cache bursts and desktop scheduling limit short-window interpretation. Allocation is per attempted call, including timeouts, not per successful ID. These measurements do not prove sustained capacity, other-profile performance or distributed uniqueness.

## Operational limits

- Schema v2 and explicit DataSource opt-in require migration. Old allocators must not run alongside new allocators. The default epoch and format stay unchanged; identity-format changes require a separately distinguished namespace.
- Non-reuse trades lifetime registration capacity for conservative safety. Default standard/high-throughput/javascript groups allow 262144/65536/1024 committed incarnations respectively; failed initialization after commit consumes capacity. There is no automatic recycling. A new group name alone does not partition numeric IDs.
- Database restore/failover must preserve all reservations and the nondecreasing cursor. Status, age, tokens and optional host metadata do not provide liveness or runtime fencing. No crash-safe last-issued watermark is claimed.
- Non-cooperative supplied JDBC drivers may finish cleanup late despite bounded caller waiting. A crashed JVM may never update termination metadata. Neither condition releases a worker number.
- TiDB, unexecuted JVM versions, production HA/migration exercises, native images, JPMS, sustained performance, remote CI, signing and publication are NOT_RUN. Javadoc's optional upstream annotation warnings are retained transparently.
- Unrelated worktree changes were preserved. No commit, tag, push, publication or remote workflow execution was performed.
