# Changelog

## Unreleased

- Group Boot-generation examples under the unpublished samples/id aggregator.
- Add a repository-owned IntelliJ Google Java Style XML and retain Maven formatting enforcement.
- Remove the redundant configuration/style ADR; keep its rules in the component contract and coding guide.
- Present verification outcomes with explicit status icons and preserve NOT_RUN for stopped checks.

- Keep only the independent ID SDK under module/; place all Boot integration under the ID starter family.
- Consolidate ID documentation under docs/id with one complete configuration and a lifetime table.
- Expand public method contracts and configuration metadata descriptions; retain author jack.
- Add tests binding the actual guide configuration; execution is stopped by the latest user instruction.

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

See [migration](docs/id/migration.md) and [verification](docs/testing/verification.md). Earlier implementation and experiment history remains in Git and `test/evidence/`.
