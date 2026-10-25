# Migration from the pre-redesign snapshot

The baseline `f86b741` was unreleased. All new coordinates use group `com.smartscity` and version `0.1.0-SNAPSHOT`. The coordinate map below records the old and current ownership.

1. Replace `smartscity-starter-bom` with `smartscity-dependencies`.
2. Replace `smartscity-id-spring-boot-starter` with `smartscity-starter-id` on Boot 2.7/3, or `smartscity-starter-id-boot4` on Boot 4. Do not use both.
3. Standalone users replace all `smartscity-id-api`, `smartscity-id-spi` and `smartscity-id-core` dependencies with `smartscity-id`. Direct adapter users now select the corresponding combined `smartscity-starter-id` or `smartscity-starter-id-boot4`; the separate adapter artifacts are removed.
4. Keep your application's Boot BOM. Importing the Smartscity BOM does not select or upgrade Boot. Applications should not inherit the internal build parent merely to consume a starter.
5. Java imports under `com.smartscity.id.api`, `.core`, `.spi` and `.autoconfigure` are unchanged. Default ID layout and epoch remain unchanged; optional epoch/width configuration is additive. The registry schema and automatic DataSource selection change as described below. Recompile applications to refresh Maven coordinates.
6. Boot 4 exclusions/order references to `IdBoot4HealthAutoConfiguration` become `com.smartscity.id.autoconfigure.IdHealthAutoConfiguration`. This is the only renamed production class; it is a framework configuration class, not the user ID API. Boot 4 adapter requires Java 17 bytecode.
7. Automatic module names now follow the artifact names with dots, prefixed by `com.` (for example `com.smartscity.id` and `com.smartscity.starter.id.boot4`). Update experimental module-path consumers; JPMS is not a verified support target.
8. Build selectors now use paths such as `-pl module/smartscity-id`, or Maven artifact selectors `-pl :smartscity-id`. Scripts moved from `testing/` to `test/`. Empty testkit is removed without a replacement.

No relocation artifacts or deprecated wrapper classes are added for this unreleased baseline. Existing raw evidence retains its original path text and hashes; use it only to inspect historical results. English current documentation replaces prior planning pages; previous narratives remain in Git.

## Coordinate map


| Previous root module | New location / artifact | Reason |
|---|---|---|
| Root parent `smartscity-starter` | `build/smartscity-parent` | Reusable build conventions; root remains aggregator |
| `smartscity-starter-bom` | `build/smartscity-dependencies` | Production versions only |
| `smartscity-id-api`, `smartscity-id-spi`, `smartscity-id-core` | `module/smartscity-id` | One zero-dependency domain artifact; unchanged Java packages |
| `smartscity-id-spring-boot-autoconfigure` | `starter/smartscity-starter-id/boot2-3`, `starter/smartscity-starter-id/boot4` | Combined starter/integration binaries with standard source trees |
| `smartscity-id-spring-boot-starter` | `starter/smartscity-starter-id/boot2-3`, `starter/smartscity-starter-id/boot4` | Select exactly one framework generation |
| `smartscity-id-testkit` | Removed | Empty placeholder |
| `smartscity-id-compatibility-tests` | `test/smartscity-id-compatibility-tests` | Independent application BOM and generation profiles |
| `smartscity-id-benchmark` | `test/smartscity-id-benchmark` | Opt-in JMH; never a production dependency |
| `testing/` | `test/` | Shared verification scripts and historical evidence |

## Identity-format migration

An ID does not encode its epoch or bit widths. Treat both as immutable namespace metadata. The default remains 2026-01-01T00:00:00Z and the existing profile layout; explicitly pin these values when rolling out this refactor to an existing installation. The format itself needs no rewrite, but the registry requires schema v2 below. Legacy constructors and presets retain their format; `BitsAllocator.EPOCH` is deprecated but retained as a default-only alias. New code reads `allocator.getLayout().getEpoch()`.

To change a format:

1. Inventory producers, consumers, stored IDs, backups and decoders. Record the old format and preserve decoding support for it.
2. Allocate a new externally distinguished namespace (separate key space or explicit version discriminator). A new database group alone does not guarantee distinct numeric IDs.
3. Stop/drain old producers and buffered work according to their allocation protocol; do not hot-reload epoch or widths or mix formats in one group.
4. Register the new group through normal startup with the new epoch/widths. Do not overwrite an old group row to bypass mismatch validation. Preserve the nondecreasing group allocation cursor and all committed worker reservations.
5. Route new writes to the new namespace, decode each stored ID using its recorded format, and verify readiness/uniqueness under the real deployment's allocation assumptions before cutover. Rollback must use safe allocation and the matching namespace, never resume stale ownership.

Custom configuration does not strengthen distributed uniqueness. The previous implementation accepted some theoretical timestamp widths that cannot fit millisecond timestamps; these now fail at construction rather than failing during decoding/observation.

## Registry schema v2

This is a breaking adapter/schema change for the unreleased baseline. Do not run old and new allocators together. The SDK deliberately has no automatic DDL migration or mixed-version bridge.

1. Inventory every producer, existing group format and historical worker allocation. Quiesce all old producers and prevent their restart. Back up both old tables and the format catalog.
2. Prefer a new externally separated key space with fresh v2 tables from the adapter resources. A new group name or fresh database alone does not make numeric IDs disjoint from historical IDs.
3. To retain the same numeric namespace, prove the complete set of historically used encoded workers. Set the v2 cursor above every used worker, retain corresponding reservations/instance tombstones, and validate format metadata before enabling producers. Previous modulo allocation may already have used all slots; in that case there is no capacity to migrate safely in place. Incomplete history cannot justify resetting the cursor. No universal migration SQL can infer this proof from the old status column.
4. Test migration and rollback in an isolated database. Preserve old records for decoding/audit. Never roll back registry state or start the old allocator against a migrated namespace.
5. Explicitly set `database.use-application-data-source=true` when adopting an application DataSource; otherwise configure a dedicated URL. Both together are rejected. Connection pools remain application-owned.

V2 adds surrogate group identity, configuration version, bounded allocation cursor and lifecycle timestamps. Workers reference the group and record a distinct worker number, incarnation identity, ownership token and optional metadata. ONLINE/OFFLINE becomes REGISTERED/ACTIVE/STOPPED/FAILED. There is no heartbeat or age-based reuse. `worker-attempts` still binds and validates for compatibility but no longer controls allocation retries. Removed low-level repository `insert`/`offline` calls are replaced by `register` and receipt-based `started`/`stopped`; direct adapter integrations must recompile and migrate. `IdGenerator` and existing exception Reason enum names remain stable; exception display text now includes stable codes.

## Starter-family layout revision

Existing starter coordinates remain unchanged. Direct users of `smartscity-id-spring-boot` / `smartscity-id-spring-boot4` must replace them with the respective combined starter. Java packages, property names, schemas and ID behavior are retained; the new starter also brings the standard Boot starter dependencies. Review transitive logging dependencies when migrating a direct-adapter consumer.

The family POM (`smartscity-starter-id-family`) is an unpublished aggregator only. Published children inherit `smartscity-parent` directly. Build selectors become `-pl starter/smartscity-starter-id/boot2-3` or `-pl starter/smartscity-starter-id/boot4`; artifact selectors for the starters are unchanged. No `src/shared` or custom source roots remain. Automatic module names are `com.smartscity.starter.id` and `com.smartscity.starter.id.boot4`; JPMS remains NOT_RUN.

ID documentation moved to `docs/id/`; module READMEs link here. Unit, integration, compatibility and packaged-artifact verification for this revision are NOT_RUN not executed under the latest user instruction. Prior PASS evidence cannot certify the new coordinates/packaging.

## Cached logical time

ADR-0011 restores future-second pre-generation following the user's clarification. The ID bit format, configured/default epoch, worker registry schema and coordinates are unchanged. Existing formats continue to decode. This is a timestamp-behavior change: IDs can encode future logical time, so review any use of decoded timestamps for business ordering, event time, expiration or range partitioning. Record those values separately from ID allocation time. Sustained throughput consumes logical seconds and can shorten time-field service life. Exhaustion is terminal; no wrap or implicit epoch migration occurs.

Drain and close old instances through their normal lifecycle and acquire fresh, non-reused worker allocations for replacements. Never reset the registry cursor or reuse a worker to adopt this change. Earlier benchmark results describing a wall-second sequence ceiling apply only to the old artifact, not to the restored cached path.
