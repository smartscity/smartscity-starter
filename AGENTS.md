# Smartscity Starter Agent Guide

## Scope and authorization

Follow the current user instruction for scope and execution authorization. Preserve unrelated changes. Local commit authorization does not include push, tag, publication or remote workflow execution. Keep benchmarks opt-in.

## Before changing code

Read [documentation](docs/README.md), the engineering rules below, accepted ADRs, the owning docs/<component>/README.md and USER_GUIDE.md and [test inventory](docs/id/testing.md). Current source of truth: latest user instruction, accepted ADR, owning contract, compatibility matrix, test evidence, implementation. Historical logs prove only their recorded snapshot.

## Repository rules

- Group examples by component under `samples/<component>/`; keep each application in its own standard Maven module.
- Root responsibilities: `build/`, `core/`, `module/`, `starter/`, `test/`, `samples/`, `docs/`, `.github/`.
- `build/smartscity-parent` owns compiler, dependency and plugin versions, quality gates and release conventions. Root POM only assembles the reactor. Never inherit the Boot parent.
- `smartscity-dependencies` manages only Smartscity production coordinates. It must not import Spring, Jackson, Netty or test dependency management.
- Prefer one domain artifact with packages. Split only for demonstrated dependency, compatibility or release needs. No empty projects, circular dependencies or shared utility dumping grounds.
- `smartscity-id` has zero production dependencies. All framework, JDBC and ZooKeeper integration lives under starter/. Boot 2.7/3 and Boot 4 are mutually exclusive combined starter binaries; no cross-generation runtime dependency.
- Each starter family has an unpublished aggregator and the minimum necessary combined integration/starter children. Published children inherit the published build parent directly. Use standard Maven source/resource/test roots; no src/shared or source injection. No unrelated capability activation. Tests, samples and benchmarks never become production dependencies.
- Preserve ID encoding, epoch, time/lease failure semantics and tests during structural changes. Never imply unconditional uniqueness, silently reuse node 0, ignore rollback or wrap sequences.
- Public types, configuration, coordinates and compatibility changes require an ADR, migration notes and verification. Keep existing public Java packages unless a migration is necessary.
- All maintained documentation and code comments are English. Navigation is locale-aware, with English as the only currently available locale. Do not create empty translations.
- Build with JDK 21 and the pinned Maven wrapper. Java 9 bytecode is not proof of Java 9–27 runtime support. Boot 4 adapter bytecode is Java 17.

## Verification and delivery

When execution is authorized, run meaningful correctness tests and the artifact boundary checker. Preserve PASS, FAIL, NOT_RUN, PARTIAL and BLOCKED. Inspect dependency convergence, generated metadata, imports registration, conditional backoff, binding, startup and shutdown. Compare source behavior and public API, not only successful compilation. Use only owned temporary database/coordination instances for integration and fault tests. Keep benchmark execution excluded.

Use detailed English Conventional Commit messages if committing. Commit/tag/push/publication follow the actual authorization. Review final state ownership, dependencies, patterns and evidence before delivery.

## CodeGraph

When `.codegraph/` exists, use `codegraph_explore` or `codegraph explore "<question>"` before text searches or reading code to locate/understand symbols. If absent, skip it and do not index automatically.

## Style and documentation

Follow the official [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html): two-space indentation, braces for control-flow bodies, explicit imports, one statement per line and meaningful English Javadoc. Pinned Spotless/google-java-format runs at Maven validate. Apply with `./mvnw -Pverification,samples -pl '!build/smartscity-dependencies' spotless:apply`; the independent production-only BOM intentionally does not inherit build plugins. Do not format generated sources or historical evidence.

Every maintained top-level Java type and public nested configuration type has a meaningful class Javadoc and `@author jack`. This author requirement is project policy, not a Google requirement. Use a summary sentence, `<p>` for subsequent paragraphs and correctly ordered block tags. Public factories and nontrivial methods use multiline Javadoc with meaningful @param, @return and @throws where applicable, including nullability, ownership transfer, concurrency and cleanup; do not add empty tags or boilerplate to obvious accessors. Release Javadoc checks syntax, references and HTML with doclint.

The IDE scheme is [Google Java Style XML](build/style/google-java-style.xml); the build parent owns the authoritative formatter version.

Comments explain contracts, failures, concurrency, resource ownership or non-obvious decisions. Remove decorative banners and narration of obvious code. Preserve stable public names for compatibility.

Each component owns docs/<component>/README.md for design and USER_GUIDE.md for usage, plus its compatibility, migration, tests and domain ADRs. Module and starter READMEs are short pointers only. Global docs contain shared architecture/policy and navigation. Maintain one complete configuration example and one reference table; documentation binding tests must read the actual example. English is the only maintained locale. Do not add empty translation directories or .gitkeep placeholders.

Epoch and bit widths form one immutable IdLayout. Keep legacy defaults and property names stable. Validate the complete format before allocation; use the same format for generation, decoding and JDBC group checks. Never hot-reload or silently migrate a live identity namespace.

## Ownership and pattern review

Before nontrivial implementation, identify each object's responsibility, invariant, state owner, collaborators, lifetime and thread ownership. Keep state and its protecting rules together. Entry points assemble and delegate; domain code stays independent of frameworks and I/O. Bound every queue, executor, retry and resource lifetime. Distinguish acceptance, completion, cancellation, timeout and unknown outcomes. Supplied DataSources remain application-owned; observation must not alter readiness or uniqueness.

Evaluate patterns for real problems and record the decision briefly:

| Trigger | Review |
|---|---|
| Three related mutable fields | Encapsulate their invariant in one owner |
| Third repeated state/policy branch | Centralize transition/strategy only if it clarifies ownership |
| Four significant construction collaborators | Reuse the composition root and its cleanup responsibility |
| Five public parameters or optional combinations | Use validated immutable options or an existing builder |
| Unrelated responsibilities | Separate by state and reason for change, not line count |

Reuse existing TimeSource/WorkerLease ports, JDBC/ZK adapters, IdGeneratorFactory, GeneratorOptions and combined integration/starters. IdLayout is the immutable identity-format value. Do not introduce speculative facades, state hierarchies, utilities, Lombok, Unsafe, JDK internals or reflection in domain/API code. No multi-release JAR or module-info is needed. Before delivery, review state/resource ownership, dependencies, failure paths, preserved assertions, current docs and actual evidence; compilation alone is insufficient.

## Worker registry

Follow ADR-0009. Group format and monotonic cursor are separate from worker registration identities. Use one transaction and database uniqueness constraints; never map a row ID modulo worker capacity. No committed worker reuse, even after shutdown or expiry; fail closed on exhaustion and unknown commit. Registration tokens protect metadata updates, not local issuance. No JDBC heartbeat, pool or generation-path query. Resolve application DataSources only with explicit opt-in and never close them. Schema migration is operator-managed; no implicit upgrade, cursor reset, status/age-based reclamation or old/new allocator mixing. Preserve UTC event metadata and do not invent a crash-safe last-issued timestamp without a durable protocol.
