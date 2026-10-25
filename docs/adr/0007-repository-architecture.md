# ADR 0007: Responsibility-based repository and framework binaries

Adapter packaging and shared-source layout are superseded by [ADR-0010](0010-component-families.md).

Status: ACCEPTED / IMPLEMENTED_UNRELEASED, 2026-10-25. Authority: the user's repository redesign instruction explicitly requests the audit followed by implementation.

Adopt `build/core/module/starter/test/samples/docs/.github` responsibilities. Merge API/SPI/core into `smartscity-id`; retain Java types. Remove the empty testkit. Introduce independent parent and production-only BOM. Preserve existing algorithms and tests. See [coordinate migration map](../id/migration.md#coordinate-map).

Keep Spring/I/O adaptation outside the zero-dependency ID domain because standalone use is real and already tested. Compile shared adapter sources separately for Boot 2.7/3 and Boot 4, with generation-specific health configuration. Boot 2.7/3 adapter and domain target Java 9; Boot 4 adapter targets Java 17. Do not package both health API generations in one binary. Compatibility remains restricted to the exact tested versions/JDKs.

The public user API, property names, exception reasons, encoding, epoch and default failures remain unchanged. Artifact coordinates and the Boot 4 health configuration class change as documented in [migration](../id/migration.md). No new wrappers or generic abstractions are introduced. Runtime state ownership and lifecycles remain unchanged.

Use English current documentation and locale-aware navigation. Adopt the existing Apache 2.0 license text as the project LICENSE. Prepare CI and manual Central staging configuration; execution of publication remains outside this task. The release operator must verify repository identity, namespace ownership, signing credentials and release version.

This supersedes ADR-0001's all-artifact Java 9 target, ADR-0002/0006's single adapter artifact, and previous root module/README language rules. It preserves ID domain semantics and evidence requirements.
