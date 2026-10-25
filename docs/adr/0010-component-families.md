# ADR 0010: SDKs, starter families and component documentation

Status: ACCEPTED / IMPLEMENTED_UNRELEASED, 2026-10-25.

Keep only framework-independent SDKs under `module/`. The ID SDK retains its zero production dependencies and existing API, generation, clock and lease semantics. Group all Boot integration under `starter/smartscity-starter-id/`, an unpublished aggregator with coordinate `smartscity-starter-id-family`. It is not a runtime dependency and is not the parent of published artifacts.

Use two combined starter modules: `boot2-3` publishes `smartscity-starter-id`; `boot4` publishes `smartscity-starter-id-boot4`. Both depend directly on the SDK and their Boot starter. Boot 4's health API and Java baseline require a separate binary; Boot 2.7/3 have historically used the same compatible API surface. Do not add a Boot-2-only module without evidence. Remove the two separate `smartscity-id-spring-boot*` coordinates from the BOM and reactor; direct consumers migrate to the corresponding combined starter. Existing Java packages stay unchanged.

Every module uses its own standard `src/main/java`, `src/main/resources`, `src/test/java` and `src/test/resources` directories. Remove shared/custom source roots, build-helper source injection and cross-generation runtime dependencies. The small generation-neutral adapter implementation is maintained in both standard trees; the existing repository checker guards equality except for the health implementation. This explicit maintenance cost is preferable to another common framework artifact or nonstandard source assembly. No new runtime abstraction or state owner is needed.

| Problem | Pattern decision | Reason |
|---|---|---|
| Standalone use versus framework lifecycle | Retain ports and adapters | SDK owns local generation; starter factory owns external registration and assembly |
| Different health APIs | Two generation-specific binaries | No reflection bridge or mixed framework signatures |
| Grouping and publishing | Aggregation without inheritance | Published POMs inherit the existing published build parent, never an unpublished family POM |
| User configuration | Retain immutable options and existing factory | No new configuration namespace, policy or facade |
| Documentation drift | One component-owned README/USER_GUIDE | Module-local files are navigation pointers; migration, compatibility and usage belong to ID |

The canonical ID documentation is `docs/id/README.md` and `docs/id/USER_GUIDE.md`. ID-specific decisions remain under `docs/id/adr/`; global documentation covers ecosystem architecture, release and verification navigation only. The main guide contains one complete YAML example and one property reference. Binding tests read that exact example instead of a copy. Public factory Javadoc describes null handling, ownership transfer, failure cleanup, caller concurrency and required closing. Authors remain jack.

This supersedes ADR-0007's adapter/source layout, the previous module-local documentation ownership and the old dependency-only starter rule. It does not change ID encoding, schema v2, bounded non-reuse, error codes or failure semantics. Compilation/static checks are separate from deferred tests and deployment verification.

References: [Spring Boot repository layout](https://github.com/spring-projects/spring-boot/tree/v3.5.16/spring-boot-project), [custom starter conventions](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html#developing-auto-configuration.custom-starter), [Maven standard layout](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html), [aggregation versus inheritance](https://maven.apache.org/guides/mini/guide-multiple-modules.html).
