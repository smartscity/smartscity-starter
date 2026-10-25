# Distributed ID: design and contract

The user API remains `com.smartscity.id.api.IdGenerator`: `getUniqueId()`, `parse(long)`, `snapshot()`, and idempotent `close()`. `IdParts`, `IdSnapshot` and `IdException` retain their signatures. There is no batch API; call repeatedly when needed.

## Artifact ownership

| Directory | Published artifact | Responsibility |
|---|---|---|
| `module/smartscity-id` | `com.smartscity:smartscity-id` | Framework-independent API, immutable format, generation and ownership/time ports; zero production dependencies |
| `starter/smartscity-starter-id/boot2-3` | `com.smartscity:smartscity-starter-id` | Boot 2.7/3 binding, JDBC/ZooKeeper adapters, lifecycle and optional observers |
| `starter/smartscity-starter-id/boot4` | `com.smartscity:smartscity-starter-id-boot4` | Same integration contract with the Boot 4 health API and Java 17 baseline |

The outer starter family is an unpublished Maven aggregator, not a library or published parent. Choose exactly one starter generation. Each child has ordinary Maven source/resource/test directories and inherits the published build parent directly. Generation-neutral adapter files are mirrored and checked for equality; only health API source differs. There is no shared source root or cross-generation runtime dependency. See [ADR-0010](../adr/0010-component-families.md).

Start with the [user guide](USER_GUIDE.md). [Migration](migration.md), [compatibility](compatibility.md), [test inventory](testing.md), [benchmark](benchmark.md) and [ID decisions](adr/README.md) belong to this component.

## Encoding and uniqueness prerequisites

Second-resolution Snowflake encoding with default epoch **2026-01-01T00:00:00Z**. The standard 64-bit value has one zero sign bit, 30 timestamp bits, 18 worker bits and 15 sequence bits:

`id = (secondsSinceEpoch << (workerBits + sequenceBits)) | (worker << sequenceBits) | sequence`

| Profile | Timestamp / worker / sequence bits | Logical time range from epoch (years) | Last encodable logical UTC second at default epoch | Lifetime worker allocations per group | IDs per worker per logical second |
|---|---|---:|---|---:|---:|
| standard | 30 / 18 / 15 | 34.03 | 2060-01-10 13:37:03Z | 262,144 | 32,768 |
| high-throughput | 30 / 16 / 17 | 34.03 | 2060-01-10 13:37:03Z | 65,536 | 131,072 |
| javascript | 28 / 10 / 15 | 8.51 | 2034-07-04 21:24:15Z | 1,024 | 32,768 |
| javascript, 16 sequence bits | 27 / 10 / 16 | 4.25 | 2030-04-03 10:42:07Z | 1,024 | 65,536 |
| javascript, 17 sequence bits | 26 / 10 / 17 | 2.13 | 2028-02-16 17:21:03Z | 1,024 | 131,072 |

Lifetime is `2^timestampBits / (365.2425 * 86400)` years from the configured epoch, not from installation. The last second is `epoch + (2^timestampBits - 1)`; exhaustion never wraps. Moving the epoch moves the dates but requires a format migration. These are capacities per logical second, not wall-clock throughput limits. Cached generation can advance future logical seconds, so sustained high rates consume the timestamp range faster than wall time. The listed years are not guaranteed process service life. Zero is reserved, so the epoch-zero/worker-zero second has one fewer usable ID. JavaScript variants use 53 effective bits; changing time/sequence tradeoffs shortens lifetime from the configured epoch. IDs are positive `long`; zero is reserved. Field overflow and epoch exhaustion fail rather than wrap. IDs carry logical allocation time, which can lead wall time or precede consumption from the buffer. Use an independent event timestamp for business time and TTLs. Concurrent completion order and cross-instance order are not guaranteed to increase.

A group uses one layout, epoch and allocation source. Different groups/layouts do not automatically occupy disjoint numeric ranges. The JDBC adapter allocates bounded worker numbers from a locked group cursor, with a separate historical registration identity. Committed worker numbers are never reused. The registry, constraints and nondecreasing cursor must survive failover, backups and restore; a stale database snapshot can invalidate uniqueness. Exhaustion fails closed. Standalone users must supply an equally safe WorkerLease protocol.

JavaScript additionally acquires an ephemeral ZooKeeper worker node before readiness. Disconnection pauses generation; the same session may resume after confirming ownership if no terminal failure has been published. Clock validation remains producer-driven, including after reconnect; a resumed cached call does not imply a new clock sample. Expiry terminates that generator and invalidates cached work. A new SDK instance receives a fresh worker, never the expired worker. No runtime reallocation occurs. Database status is observational, not liveness proof or fencing. These guarantees require a single consistent registry, exclusive allocation and unmodified format metadata; externally bypassing the protocol can cause duplicates.

## Time, concurrency and failures

Generation uses one startup wall-clock anchor plus elapsed `nanoTime`; database calibration and generation share that anchor. Startup checks database time including network delay; SQL NULL, arithmetic overflow, excessive skew or inability to validate prevent readiness. The producer checks local wall-clock deviation without reanchoring before each refill chunk and periodically while idle. Three failed attempts to sample within 1 ms or deviation beyond tolerance publish a terminal failure. Cached generation borrows future logical seconds when its sequence is exhausted; logical timestamp exhaustion fails closed. Drift validation uses the original wall/monotonic anchor, independently of logical allocation time. Consumers check local lease/closed/published-failure state, but do not take fresh clock samples. An undetected clock fault can coexist with successful cached issuance until the producer next validates; the idle 1 ms park request is not a scheduling or detection bound. Transient faults between validations may be missed. There is no automatic recovery after a published terminal clock failure. See [ADR-0012](adr/0012-producer-clock-validation.md).

One producer owns the logical second/sequence cursor. It validates ownership/time between bounded 256-ID refill chunks, encodes contiguous ranges within each logical second, and publishes each accepted range with one tail release. The fixed-size primitive ring uses no executor queue. Consumers acquire the published tail before claiming with CAS; only the successful claimant may read and then release its slot. Per-slot reclamation generations prevent overwriting a paused claimant, and cursors never silently wrap. A claimed ID that subsequently fails is discarded, never reinserted. A paused claimant can delay slot reuse, so absence of explicit locks is not a strict lock-free progress guarantee.

Consumers keep pre/post-claim local state validation. Each poll attempts up to eight CAS claims; a private contention result permits up to eight additional bounded polls before a short park. Empty-cache polling retains its existing interval. Every retry checks interruption, ownership, published failure and the original total wait budget. The producer coalesces wakeups and rechecks low occupancy and slot reclamation after arming its parked flag, including when its previous refill observed a full ring. Suspended ownership and unreclaimed slots still park rather than spin.

The default 10 ms wait is one total call budget for buffer exhaustion and contention. Timeout raises `TIMEOUT`; interruption raises `INTERRUPTED` and preserves the flag. Terminal clock/worker state and closure reject new calls. An in-flight call cannot be atomically withdrawn. Reasons are `CONFIGURATION`, `STARTUP`, `CLOCK`, `CAPACITY`, `TIMEOUT`, `INTERRUPTED`, `COORDINATION`, `CLOSED`, `INTERNAL`; callers must inspect the reason, not parse messages.

`snapshot()` is approximate read-only observation; latency quantiles are power-of-two bucket upper bounds, not benchmark evidence. `close()` stops production and attempts database offline marking and ZooKeeper release. Producer join is bounded by 1 s; external cleanup shares `shutdown-timeout`. Forced process termination may skip the offline update. Startup cancellation preserves cleanup, rejects late results and does not transfer ownership of an application's DataSource.

Producer admission compares freshly validated milliseconds with immutable layout bounds before converting to relative seconds. A normal consumer call performs local admission, a ring claim, local post-claim admission and exact metrics recording; unavailable-claim retries live in a separate bounded wait path. Successful latency buckets supply the generated count, while separate failed-call buckets retain unsuccessful durations in overall latency. Every completed call is recorded without sampling; snapshots aggregate a fixed number of counters.

## Format configuration

`IdLayout` is the immutable epoch/width value. `BitsAllocator` validates fields and encodes/decodes; `GeneratorOptions` captures the allocator and runtime options. The clock retains its single anchor and the producer uses the configured epoch. No Spring, JDBC or ZooKeeper dependencies enter this artifact. Public packages remain unchanged.

Defaults remain 30/18/15 bits and epoch 2026-01-01T00:00:00Z. Widths must be positive and total at most 63; timestamps must fit the millisecond range used by clock observation. Epochs are whole-second instants. Zero is reserved and overflow never wraps. Custom formats do not add namespace bits or strengthen uniqueness assumptions.

Rollback beyond tolerance remains terminal; sequence exhaustion advances the cached producer to the next logical second. Actual empty-buffer/claim contention still uses the existing total call budget. The explicit-second low-level cursor retains its zero-on-exhaustion behavior. Clock sampling retries/window, polling cadence, the producer join bound and metric bucket geometry remain internal implementation bounds, not selectable policies. Buffer sizing, refill threshold and call/clock budgets are already options; registration attempts and external lifecycle/session budgets remain adapter configuration. Reserved zero, checked shifts and slot visibility are algorithm invariants.

## Registry ownership

`IdProperties` binds configuration; `options()` captures an immutable generation format. `WorkerInstance` captures one incarnation's UUID and optional application/host hints. `IdGeneratorFactory` validates before I/O, owns partial-start cleanup and transfers the completed allocation to the generator. `JdbcWorkerRepository` owns short transactions, not a pool or the application's DataSource.

The group table owns identity-format metadata and a monotonic allocation cursor. One transaction creates or locks the group, compares epoch, layout and configuration version, rejects duplicate instance identity, increments the bounded cursor and inserts a worker registration. Concurrent creation handles only the expected unique conflict, rolls back to a savepoint, then reads the locked committed row. A receipt is returned only after commit; uncertain commits fail startup without automatic retry.

| Identity | Meaning |
|---|---|
| Group ID/name | External namespace and immutable format; different groups can produce equal numbers |
| Registration row ID | Database-generated historical record identity; never truncated into an ID |
| Worker ID | Encoded integer allocated from `[0, 2^workerBits)` within the group |
| Instance ID | UUID per generator incarnation, or explicit unique process identity |
| Registration token | Random capability matching lifecycle updates to the committed registration; not an issuance fence |

Worker numbers are **never reused**, including after failed initialization, graceful shutdown, crash or ZooKeeper expiry. Constraints enforce unique `(group_id, worker_id)`, `(group_id, instance_id)` and tokens. Allocation locks one group row and uses indexed lookups; it does not scan worker history. Exhaustion fails with `ID-004`. Groups allocate independently.

## Lifecycle and resources

Startup registers `REGISTERED`, validates database/local time, initializes the local generator, then writes `ACTIVE` before returning it. Failed local startup attempts `FAILED`; normal shutdown first closes local generation and then attempts `STOPPED`. Timestamps are UTC. `registered_at`, `started_at`, `shutdown_at` and `updated_at` describe events, not continuous liveness. An ambiguous activation may leave ACTIVE or FAILED metadata; neither permits reuse.

Generation uses local state only: no SQL, registry lookup, heartbeat or distributed lock. The javascript profile still observes its ZooKeeper session through local state; it does not renew ownership through JDBC. Its occupancy check is additional protection, not authorization to reuse a worker.

Dedicated URLs use unpooled driver connections. Application DataSources are resolved only with explicit opt-in and are never closed by the SDK. Every borrowed connection, statement and result set is scoped to an operation. Startup bounds caller waiting and rejects late results; shutdown is idempotent with a shared cleanup budget. A non-cooperative supplied driver can finish late. No Java mechanism can forcibly reclaim such a driver safely; eventual cleanup retains ownership of its resources.

A crash may leave REGISTERED or ACTIVE indefinitely. Status, age and token do not prove the old process is dead or prevent its local issuance. There is no `last_issued_second` column because startup/shutdown-only SQL cannot maintain a crash-safe issuance watermark. Never reclaim numbers based on status, record age or missing heartbeats. Do not roll back the cursor during restore, failover or retention; destructive reset requires a separate external namespace.
