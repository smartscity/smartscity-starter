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

Do not combine the two starters. Boot before 2.7 is unsupported. Bytecode minimums do not certify every runtime version. Consult [compatibility and historical evidence](compatibility.md); tests for this revised packaging are NOT_RUN under the latest user instruction.

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
