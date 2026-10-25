# Repository architecture decisions

| Decision | Status | Ownership |
|---|---|---|
| [0007: Responsibility-based repository](0007-repository-architecture.md) | Accepted; adapter layout superseded by 0010 | Root responsibilities, build parent and BOM |
| [0010: SDKs and starter families](0010-component-families.md) | Accepted / implemented unreleased | SDK-only module directory, combined starters, standard Maven roots, component docs |

Component decisions are maintained with their contracts: [ID decisions](../id/adr/README.md). ADRs 0001–0006 remain in Git at `f86b741:docs/adr/`; later decisions preserve ID encoding and failure semantics unless explicitly stated.
