# Third-party inputs

Production dependencies are declared in the module POMs: Spring Boot/Spring, SLF4J, and optional ZooKeeper/Micrometer/health adapters. The application supplies its JDBC driver. Test dependencies and downloaded database test binaries do not enter the production Starter dependency graph.

The supplied legacy source was reviewed as a design reference; its source archive, hashes and findings are recorded in [the input audit](docs/proposals/id-v1/legacy-input-audit.md). This implementation does not copy the legacy source files.

Dependency license metadata remains available in the respective dependency POMs. The project license has not yet been selected; no remote publication has been performed.
