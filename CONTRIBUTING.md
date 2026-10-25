# Contributing

Read [AGENTS.md](AGENTS.md), the [architecture](docs/architecture/modules.md), and the affected domain contract before changing implementation. Public API, coordinate and compatibility changes need an ADR and migration instructions. Ordinary tests belong beside their owner; cross-module tests belong in `test/`.

Use JDK 21 and `./mvnw`. Run `./mvnw -B -ntp -Pverification,samples verify`, `python3 test/check-artifacts.py`, and relevant compatibility profiles. See [test inventory](docs/id/testing.md) for isolated database and fault gates. Build success does not establish uniqueness or untested compatibility. Keep benchmarks opt-in and bounded.

Write documentation and comments in English. Use detailed English Conventional Commits with concrete changes, tests and explicit limitations. Preserve unrelated edits. A local commit does not authorize push, release or publication.

Add a component only for an implemented requirement. Start with a domain module and starter; do not clone ID's framework-generation split unless needed. Centralize build versions in the parent and list production artifacts only in the public BOM. Keep the application's Boot BOM in control of external dependencies.

## Google Java Style in the editor

Import [build/style/google-java-style.xml](build/style/google-java-style.xml) in IntelliJ IDEA through Editor > Code Style > Java > Import Scheme > IntelliJ IDEA code style XML. This is the project's IDE scheme aligned with the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html), not a byte-identical copy of an upstream preset. It sets two-space indentation, a 100-column margin, explicit imports and Javadoc layout preferences. [.editorconfig](.editorconfig) supplies shared text-file defaults.

The build parent pins Spotless 2.43.0 and google-java-format 1.22.0 with GOOGLE style. That formatter remains authoritative for Maven and CI; it does not read the IDE XML. Apply formatting with `./mvnw -Pverification,samples -pl '!build/smartscity-dependencies' spotless:apply` when needed. IDE formatting can differ from google-java-format, so use the pinned Maven formatter for final normalization.

Document meaningful public API contracts and use `@author jack` on maintained types. Configuration XML cannot supply accurate purpose, ownership or failure semantics; these require source review.
