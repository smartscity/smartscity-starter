# Distributed ID user guide

This guide takes a Spring Boot application from dependency selection to its first ID. Both starters use the same configuration and API. For framework-free use, see [standalone SDK](#standalone-sdk) below. Design limits and allocation guarantees belong to the [ID contract](README.md).

## 1. Add Maven dependencies

Keep the application's existing Spring Boot parent or BOM. Add the Smartscity BOM to its dependency management, then add the ID starter and JDBC driver to its dependencies:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.smartscity</groupId>
      <artifactId>smartscity-dependencies</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.smartscity</groupId>
    <artifactId>smartscity-starter-id</artifactId>
  </dependency>
  <dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.4.0</version>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

Merge these entries into existing sections; do not replace the application's Boot dependency management. The driver version above matches this repository's build. Smartscity's BOM manages only Smartscity libraries. This unreleased snapshot must be available from your local build or configured snapshot repository; no Central release is claimed. Build instructions are in [contributing](../../CONTRIBUTING.md).

## 2. Select the Spring Boot generation

| Application | Starter artifact | Starter bytecode minimum | Selection |
|---|---|---:|---|
| Boot 2.7 / 3.x | `smartscity-starter-id` | Java 9 | Use the dependency above; Boot 3 itself requires Java 17+ |
| Boot 4.x | `smartscity-starter-id-boot4` | Java 17 | Replace only the starter artifact ID above |

Do not combine the two starters. Boot before 2.7 is unsupported. Bytecode minimums do not certify every runtime version. Consult the [compatibility requirements](compatibility.md) and [verification commands](testing.md).

## 3. Initialize the database

Create a database and apply exactly one schema before starting the application. The starter does not create or upgrade tables. Existing installations must follow [schema-v2 migration](migration.md#registry-schema-v2).

| Database | Schema | Additional setup |
|---|---|---|
| MySQL (used below) | [mysql.sql](../../starter/smartscity-starter-id/boot2-3/src/main/resources/db/mysql.sql) | Supply the MySQL JDBC driver |
| PostgreSQL | [postgresql.sql](../../starter/smartscity-starter-id/boot2-3/src/main/resources/db/postgresql.sql) | Supply the PostgreSQL JDBC driver and set `database.type` to `postgresql` |
| TiDB | [tidb.sql](../../starter/smartscity-starter-id/boot2-3/src/main/resources/db/tidb.sql) | Requires 6.5+ and the schema's `AUTO_ID_CACHE=1`; real TiDB execution remains NOT_RUN |

Both starter JARs contain identical schemas under `db/`. For the MySQL example, from the repository root, with an existing `id_registry` database and administrative account:

```sh
mysql -h 127.0.0.1 -u registry_admin -p id_registry \
  < starter/smartscity-starter-id/boot2-3/src/main/resources/db/mysql.sql
```

Provision a runtime account with SELECT/INSERT/UPDATE access to these tables. Configure `ID_DATABASE_URL` as, for example, `jdbc:mysql://127.0.0.1:3306/id_registry`; set `ID_DATABASE_USER` and `ID_DATABASE_PASSWORD` to that account's credentials. Database time must be compatible with the configured clock tolerance.

## 4. Configure the application

Use this complete `application.yml` for the MySQL command-line example. After applying the schema and setting the environment variables above, it supplies all required ID configuration. For a web application, omit `spring.main.web-application-type` so its existing web mode applies.

<!-- id-configuration:start -->
```yaml
spring:
  application:
    name: order-service
  main:
    web-application-type: none
smartscity:
  id:
    enabled: true
    group: orders
    profile: standard
    epoch: 2026-01-01T00:00:00Z
    bits:
      timestamp: 30
      worker: 18
      sequence: 15
    database:
      type: mysql
      use-application-data-source: false
      url: ${ID_DATABASE_URL}
      username: ${ID_DATABASE_USER}
      password: ${ID_DATABASE_PASSWORD:}
    buffer-size: 16384
    low-water-percent: 50
    wait-timeout: 10ms
    latency-sample-interval: 1024
    clock-tolerance: 1s
    startup-timeout: 10s
    shutdown-timeout: 2s
```
<!-- id-configuration:end -->

This is the same configuration included in both [sample applications](../../samples/README.md). `DocumentationConfigurationTest` reads this exact block to check binding; it is added but not executed for this revision. The empty password fallback supports accounts intentionally configured without a password; production credentials should come from the environment or your secret provider.

The starter allocates a new worker during startup and publishes the generator only after registration, time validation and activation. The example pins the default epoch and widths. All instances in `orders` must use the same format. Changing only a group name does not create disjoint numeric IDs. No worker-allocation selector is needed: JDBC worker numbers are always non-reusable. `profile` selects the width preset and whether ZooKeeper is additionally required.

## 5. Generate and decode an ID

Inject the shared generator into an application service:

```java
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdParts;
import org.springframework.stereotype.Service;

/**
 * Assigns identifiers to orders using the application's shared generator.
 *
 * @author jack
 */
@Service
public final class OrderIds {
  private final IdGenerator generator;

  public OrderIds(IdGenerator generator) {
    this.generator = generator;
  }

  /**
   * Allocates an ID for a new order.
   *
   * @return a positive ID in the configured namespace
   * @throws com.smartscity.id.api.IdException if generation is unavailable
   */
  public long next() {
    return generator.getUniqueId();
  }

  /**
   * Decodes an order ID using the configured format.
   *
   * @param id an ID written with this service's epoch and bit widths
   * @return its timestamp, worker and sequence
   * @throws com.smartscity.id.api.IdException if the ID does not fit the layout
   */
  public IdParts decode(long id) {
    return generator.parse(id);
  }
}
```

Spring closes its generator during application shutdown. Do not close it per request. `getUniqueId()` supports concurrent callers; completion order is not ID order. `parse()` requires the original format, which is not embedded in the ID. `snapshot().isReady()` exposes current local readiness. A user-provided `IdGenerator` bean backs off automatic construction; `enabled=false` disables auto-configuration and its observers.

## 6. Configuration reference

Every property below is relative to **`smartscity.id`**. Defaults are the values when omitted, not the explicit values chosen in the example. Duration values accept Spring Boot duration syntax such as `10ms` and `1s`. Lifecycle budgets are evaluated in milliseconds. The reference covers the binding model in both starter generations.

| Property | Type | Default | Required | Description |
|---|---|---|---|---|
| `enabled` | boolean | `true` | No | Enables automatic construction and observers |
| `group` | String | `default` | No | Registry namespace; 1–128 ASCII letters, digits, underscores or hyphens |
| `profile` | String | `standard` | No | `standard`, `high-throughput` or `javascript`; explicit widths override the preset |
| `epoch` | Instant | `2026-01-01T00:00:00Z` | No | Whole-second UTC ISO-8601 instant; immutable for a registered group |
| `bits.timestamp` | Integer | Profile-derived | All three or none | Positive timestamp width in seconds; full range must fit millisecond clock arithmetic |
| `bits.worker` | Integer | Profile-derived | All three or none | Positive worker width; total committed allocation budget is `2^worker` per group |
| `bits.sequence` | Integer | Profile-derived | All three or none | Positive sequence width; total widths at most 63, or 53 for javascript |
| `javascript-sequence-bits` | int | `15` | No | 15–17 when profile is javascript; ignored by other profiles; explicit widths override its preset |
| `buffer-size` | int | `16384` | No | Power of two from 2 through 16777216; buffering does not raise encoding capacity |
| `low-water-percent` | int | `50` | No | 1–99; occupancy threshold that wakes the producer |
| `latency-sample-interval` | int | `1024` | No | Power of two in [1, 1048576]; independent duration sampling probability 1/value; 1 restores full collection |
| `wait-timeout` | Duration | `10ms` | No | 0–1s total contention/exhaustion budget; zero fails immediately when no ID is available |
| `clock-tolerance` | Duration | `1s` | No | At least 1ms; excess local/database skew prevents readiness or terminates generation |
| `startup-timeout` | Duration | `10s` | No | 100ms–300s; caller's bounded startup budget |
| `shutdown-timeout` | Duration | `2s` | No | 1ms–30s; shared external cleanup budget, separate from the SDK's producer join |
| `worker-attempts` | int | `1024` | No | Deprecated compatibility binding, validated 1–65536; no allocation retry effect |
| `database.type` | String | `mysql` | No | `mysql`, `postgresql` or `tidb`; must match the schema |
| `database.use-application-data-source` | boolean | `false` | No | Explicitly selects a unique/primary application DataSource; omit URL |
| `database.url` | String | Unset | Unless using application DataSource | Dedicated JDBC URL; mutually exclusive with a supplied DataSource |
| `database.username` | String | Unset | As required by driver/account | Dedicated-connection username; application DataSource manages its own credentials |
| `database.password` | String | Unset | As required by driver/account | Dedicated-connection secret; never use as a metric label |
| `database.driver-class-name` | String | JDBC discovery | No | Explicit driver class when discovery is unavailable; driver must be on application classpath |
| `instance.id` | String | New UUID per generator | No | Unique incarnation, 1–128 ASCII letters/digits/`_.:-`; reusing a value in a group is rejected |
| `instance.application-name` | String | `spring.application.name`, if present | No | Diagnostic hint, at most 128 characters |
| `instance.host-ip` | String | Unset | No | Diagnostic hint, at most 64 characters; not an ownership key |
| `instance.host-port` | Integer | Unset | No | Diagnostic port, 1–65535 |
| `instance.hostname` | String | `HOSTNAME`, if present | No | Diagnostic hint, at most 255 characters; no network discovery |
| `zookeeper.connect-string` | String | Unset | For javascript | Nonempty connection string; application also supplies ZooKeeper client |
| `zookeeper.root` | String | `/smartscity-id` | No | Absolute path with letter/digit/underscore/hyphen segments; shared consistently across instances |
| `zookeeper.session-timeout` | Duration | `15s` | No | 1s–300s requested timeout; server may negotiate it |
| `zookeeper.auth-scheme` | String | Unset | Paired with auth | Authentication scheme; both auth fields present or both absent |
| `zookeeper.auth` | String | Unset | Paired with auth-scheme | Authentication data; never log it |

## 7. Advanced use

### Profiles and custom formats

For high throughput, change `profile` to `high-throughput` and remove all three explicit `bits` entries to select 30/16/17. For javascript, change `profile` to `javascript`, remove all three widths, choose `javascript-sequence-bits` (15–17), and supply the ZooKeeper dependency and `zookeeper.connect-string`. Use authenticated coordination for deployments that require it. These edits apply to the single configuration above; they are not additional YAML documents to merge.

Explicit widths always override profile-derived widths. The javascript profile still validates its sequence preset, requires ZooKeeper and enforces the 53-bit ceiling even with explicit widths. More worker or sequence bits reduce available timestamp lifetime at a fixed total width. See the [lifetime table](README.md#encoding-and-uniqueness-prerequisites). Epoch and widths cannot be hot-reloaded; an existing namespace requires [format migration](migration.md#identity-format-migration).

### Application DataSource

To reuse an application DataSource, remove `database.url`, `database.username` and `database.password`; set `database.use-application-data-source` to `true` and keep the appropriate `database.type`. Supply exactly one candidate or designate a primary bean. It remains application-owned. Dedicated URL mode uses short-lived unpooled driver connections and does not start a connection pool.

### Standalone SDK

Outside Spring Boot, depend only on `com.smartscity:smartscity-id:0.1.0-SNAPSHOT`. It has no production dependencies. The caller must obtain a valid, exclusive `WorkerLease` from its own allocator; a constant worker is not a safe distributed allocation protocol.

```java
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.core.IdLayout;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import java.time.Instant;

// lease is supplied by the application's allocation protocol.
GeneratorOptions options = GeneratorOptions.builder()
    .bits(new BitsAllocator(new IdLayout(Instant.parse("2026-01-01T00:00:00Z"), 30, 18, 15)))
    .build();
try (IdGenerator generator = new CachedUniqueIdGenerator(options, lease, TimeSource.SYSTEM)) {
  long id = generator.getUniqueId();
}
```

The producer validates time before refill and while idle. Consumers check local ownership and published failure state; they do not sample the wall clock per call. A clock fault is rejected after producer detection, which has scheduling delay and no hard detection bound. See [migration considerations](migration.md#producer-side-clock-validation).

The generator owns the lease, including cleanup after failed initialization. Lease state reads must be local, bounded and thread-safe; do not share a lease between generators. The builder's `capacity`, `lowWaterPercent`, `waitTimeout` and `clockTolerance` correspond to the values in the configuration table. For externally calibrated startup, `CachedUniqueIdGenerator.usingClock` accepts that already validated anchor; it does not perform database calibration itself.

## 8. Operations and troubleshooting

When using Actuator, include `smartscityId` in the readiness health group. Otherwise use `snapshot().isReady()`. Observe typed failures and remaining worker capacity. Raw worker IDs, instance IDs and credentials are not metric labels. Shutdown closes local generation and attempts a STOPPED update; forced termination may leave ACTIVE/REGISTERED records. Those statuses do not prove liveness and never authorize reuse.

Every committed startup registration consumes a worker permanently, including failed activation. With standard widths a group has 262,144 lifetime registrations; high-throughput has 65,536 and the default javascript format 1,024. Restarting does not reset capacity. Preserve allocation cursors, constraints and historical reservations across failover, backups and retention. Plan an externally separated namespace before exhaustion.

| Symptom / code | Action |
|---|---|
| `ID-001 CONFIGURATION` | Check types, all-or-none widths, URL/DataSource exclusivity, instance hints and profile requirements |
| `ID-002 STARTUP` | Check driver, schema, credentials, database reachability and group format; unknown commits are not automatically retried |
| `ID-003 CLOCK` | Inspect time synchronization and skew; terminal failures require safe reinitialization, not bypassing validation |
| `ID-004 CAPACITY` | Inspect epoch range or lifetime worker exhaustion; never reset the cursor or wrap fields |
| `ID-005 TIMEOUT` | Inspect producer scheduling, cache occupancy and consumer contention; sequence exhaustion advances logical time rather than throttling to a wall second |
| `ID-006 INTERRUPTED` | Respect caller cancellation; the interrupt flag is preserved |
| `ID-007 COORDINATION` | Inspect ZooKeeper session/ownership; expiry requires a new safely allocated instance |
| `ID-008 CLOSED` | Stop using the closed generator |
| `ID-999 INTERNAL` | Retain diagnostics and investigate the original cause |

Branch on `IdException.getReason()` or `getCode()`, not display text. `getDefaultMessage()`, `getDetails()` and `getCause()` expose context; do not blindly log driver exceptions containing secrets. Missing tables are not repaired automatically. Drivers that ignore cancellation may release resources late, but timed-out startup results are never enabled. There is no JDBC heartbeat, runtime worker reallocation or generation-path database access.

## Logical timestamps and cached throughput

The cached generator may generate IDs for future logical seconds. Each sequence field covers one logical second; it does not cap API throughput to that many calls per wall-clock second. At rates above that capacity, timestamp lead grows and the available time field is consumed faster. Increasing the buffer bounds memory differently but does not bound cumulative timestamp lead. Encoded timestamps must not replace event time, audit time or TTL clocks.

Worker allocations remain permanently non-reusable, including after shutdown. A fresh worker protects restart separation even when an older process generated future timestamps. No hot reload, epoch change or schema migration is performed by this behavior correction. See [migration considerations](migration.md#cached-logical-time) and [ADR-0011](adr/0011-cached-logical-time-and-refill.md).

### Capacity and waiting metrics

Latency is sampled independently of the outcome, with probability `1 / latency-sample-interval`. Successful-ID and failure-reason counts remain exact after callers complete. `IdSnapshot.getLatencySamples()` counts observed successful and failed durations; `getLatencyNanos()` sums only those observations, not all calls. Divide by the sample count for the observed mean. Percentiles describe the lifetime sample and cannot certify rare tail behavior or an SLO. With zero samples, API percentile getters retain the zero sentinel; check the sample count before using them. Micrometer exports NaN until a sample exists. Configure `latency-sample-interval: 1` (or `.latencySampleInterval(1)` in the SDK builder) for the previous full-collection semantics. The sampler never changes the call-entry deadline or capacity/clock/lease checks.

All meter names below use the existing `group` label. Standalone leases without a registry observer return UNKNOWN capacity; the remaining-capacity gauge is NaN, never a misleading zero. The JDBC starter initially exposes the registration receipt, then snapshots request a read-only background refresh at most once per 30 seconds. No observer is polled when snapshots are not requested. One read may remain in flight if a driver ignores timeouts; no additional readers are started. Close interrupts it and prevents late publication, with eventual scoped connection cleanup.

| Meter | Meaning / interpretation |
|---|---|
| `smartscity.id.worker.capacity` | Lifetime worker slots for this layout |
| `smartscity.id.worker.remaining` | Unallocated group workers at the last observation; upper bound afterward, never permission to reuse |
| `smartscity.id.worker.capacity.observed.milliseconds` | UTC start time of the retained observation; alert on stale samples, for example age over 90 seconds with regular scraping |
| `smartscity.id.worker.capacity.sample.success` | 1 only after the last completed observation succeeded; 0 for initial, unknown or failed observations; also check age |
| `smartscity.id.time.logical.milliseconds` | Last published allocation timestamp, including consumed ranges |
| `smartscity.id.time.future.milliseconds` | Nonnegative lead of that timestamp over the wall clock at observation |
| `smartscity.id.time.remaining.seconds` | Whole logical seconds after the last published second; excludes its remaining sequences |
| `smartscity.id.polls.empty` | Cold-path empty observations, not distinct requests |
| `smartscity.id.polls.contended` | Cold-path polls exhausting their bounded CAS attempts |
| `smartscity.id.consumer.parks` | Requested cold-path parks, including parks that immediately consume an unpark permit |
| `smartscity.id.latency.samples` | Actual duration observations, including sampled failures |
| `smartscity.id.latency.sample.interval` | Inverse observation probability; 1 is full collection |
| `smartscity.id.timeouts{stage=empty}` | Deadline reached after an empty observation |
| `smartscity.id.timeouts{stage=contention}` | Deadline reached after exhausted CAS retries |
| `smartscity.id.timeouts{stage=after_claim}` | A waiting caller obtained an ID after its deadline and burned it |

Poll and park counts publish when a wait completes; snapshots do not include retries still in flight. Use the remaining-capacity gauge together with freshness and your deployment rate; reserve enough workers for planned rolling deployments and failed startups. Across instances use the minimum fresh remaining value for a group, not a sum. Observe the registry cursor separately when no generator remains live. Do not change group names or reset cursors to bypass exhaustion: different groups do not automatically have disjoint numeric ranges.

Alert on future lead and declining logical capacity against application limits. A million-ID throughput result is not proof of decades of service life. Clock deviation and logical lead are different measurements. Timeout stages classify the last observed condition, not the root cause: OS scheduling, a descheduled slot claimant, producer starvation and contention still require diagnosis. Timeout diagnostic details include total elapsed time, delay before entering the wait path, cumulative park time and approximate occupancy; they describe the failed call without weakening its deadline. Thread notification preserves the 10 ms default budget; it does not promise a hard scheduling bound or zero timeouts.

## Roaring-oriented storage

Use the existing `com.smartscity:smartscity-id:0.1.0-SNAPSHOT` SDK or the matching combined starter dependency above. No new SDK artifact or production Roaring dependency is required. Applications that actually store bitmaps must add their own pinned RoaringBitmap dependency; the comparison suite uses `org.roaringbitmap:RoaringBitmap:1.6.29`. No new `smartscity.id.*` configuration option is introduced; defaults, layout, epoch, buffer size and Boot injection are unchanged.

For database primary keys, orders and transactions, keep the standard injected generator:

```java
long businessId = standardGenerator.getUniqueId();
```

When the same business entity is stored in both SQL and Bitmap, **encode that existing ID**, rather than allocate a second unrelated ID:

```java
IdEncoder encoder = RoaringIdEncoder.forVersion(RoaringIdEncoder.VERSION);
long businessId = standardGenerator.getUniqueId();
long bitmapKey = encoder.encode(businessId);
bitmap.addLong(bitmapKey);
boolean exists = bitmap.contains(encoder.encode(businessId));
bitmap.removeLong(encoder.encode(businessId));
long restoredBusinessId = encoder.decode(bitmapKey);
```

For explicitly Bitmap-oriented ID generation (idempotency tracking or account/transaction state-machine ID sets), a local view is also available:

```java
try (RoaringIdGenerator view =
    new RoaringIdGenerator(standardGenerator, new RoaringIdEncoder())) {
  long bitmapKey = view.getUniqueId();
  IdParts parts = view.parse(bitmapKey); // inverse mapping, then standard decoding
}
// The application still owns standardGenerator; the view did not close it.
```

Imports are `com.smartscity.id.api.IdEncoder`, `IdParts`, and `com.smartscity.id.core.RoaringIdEncoder`, `RoaringIdGenerator`. Both examples require standard 30/18/15 output. High-throughput and javascript presets are unsupported by encoding v1. Do not encode an already encoded value. Arbitrary custom delegates cannot be validated by inspecting their positive long output; the caller must supply the correct format.

For a direct synchronous generator, construct `SequenceIdGenerator(options, exclusiveLease, timeSource)` only after safely reserving a unique, non-reusable worker. It creates no ring or producer and checks time on each call. `BufferedIdGenerator` has the existing constructor and calibrated `usingClock` factory; it remains the production starter default. Do not share one lease between two allocating generators, even when comparing them. The deprecated low-level cursor is not a safe standalone replacement for worker registration/lifecycle.

### Spring Boot selection

The default `IdGenerator` bean continues producing standard IDs. Prefer an independent encoder bean and inject it into the Bitmap-owning service:

```java
@Bean
IdEncoder bitmapIdEncoder() {
  return new RoaringIdEncoder();
}
```

This bean does not implement IdGenerator and does not trigger generator backoff. Create a non-owning RoaringIdGenerator view inside the consuming service if needed. Do not add an unqualified second IdGenerator bean: the existing conditional-backoff and injection rules still apply. No additional starter module or automatic replacement is provided. The application's Bitmap synchronization, TTL and persistence remain application responsibilities; Roaring64Bitmap is not assumed safe for concurrent mutation.

### Migration and Raft snapshots

`CachedUniqueIdGenerator` is a deprecated compatibility adapter to `BufferedIdGenerator`, retaining constructor and `usingClock` signatures. `DefaultUniqueIdGenerator` remains a deprecated single-owner cursor with its old `next(second)` behavior; it never implemented IdGenerator. New lifecycle-managed synchronous callers use SequenceIdGenerator. Code injecting the concrete old cached class must migrate to IdGenerator because the starter factory now returns BufferedIdGenerator. Existing standard numeric IDs and decoding remain unchanged.

Persist the storage encoding name alongside every Bitmap snapshot, plus the original epoch/layout, business retention-window metadata and a schema/migration version. Every Raft replica must apply the same deterministic encoding. Leaders should replicate the original business ID; followers must not allocate another local ID. Encoding uses no local clock/randomness. Snapshot restore must validate metadata and refuse unsupported versions; a long does not self-identify its representation.

Never reinterpret existing standard keys as encoded keys. Rebuild into a separate versioned Bitmap using the inverse/source representation, validate cardinality and membership, and cut over at a coordinated log/snapshot boundary. During migration, explicitly route lookups and deletions to the matching old/new representations; use one agreed dual-read/write or replay protocol across replicas. Rollback needs the corresponding old snapshot/log boundary. This SDK supplies the encoder and rejects unknown names; it does not implement a Raft snapshot store or rolling-migration coordinator.

Use short business-time retention windows where possible and runOptimize only under exclusive mutation ownership or on a frozen copy. Low-TPS, sparse, long-lived sets are candidates for mapping; dense high-TPS or already compact runs may favor standard IDs. Heap, serialized bytes, RSS and peak construction memory differ; use the [benchmark report](benchmark.md) and representative production samples before enabling this representation.
