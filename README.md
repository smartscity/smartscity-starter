# Smartscity Starter

Independent Spring Boot extensions with framework-independent SDKs. Distributed ID is the first implemented capability; this repository is unreleased.

## Repository layout

```text
build/                              Build parent and production-only BOM
core/                               Shared infrastructure when needed
module/smartscity-id/                Standalone ID SDK; zero production dependencies
starter/smartscity-starter-id/       Unpublished family aggregator
  boot2-3/                          smartscity-starter-id: Boot 2.7/3 integration
  boot4/                            smartscity-starter-id-boot4: Boot 4 integration
test/                               Compatibility, benchmarks and evidence
samples/id/                         ID example family
  id-example/                       Boot 2.7/3 executable consumer
  id-example-boot4/                  Boot 4 executable consumer
docs/id/                            Canonical ID design and usage
.github/                            CI and delivery workflows
```

The two starters are alternatives and each includes its own integration code. All modules use standard Maven source trees. Published artifacts inherit the build parent directly; the family aggregator is not published. See [module boundaries](docs/architecture/modules.md).

## Use distributed IDs

Follow the [ID user guide](docs/id/USER_GUIDE.md): select the dependency for your Boot generation, initialize the database, copy one complete configuration, then inject `IdGenerator`. Standalone consumers depend only on `smartscity-id`. The [ID design](docs/id/README.md) explains encoding lifetime, worker capacity, ownership and failure guarantees.

Keep the application's Boot BOM. `smartscity-dependencies` manages only Smartscity production versions. Build with JDK 21 and the pinned Maven wrapper. Bytecode targets and exact tested environments are distinguished in [ID compatibility](docs/id/compatibility.md).

## Development status

The current architecture and documentation structure is accepted. **Tests and benchmarks have not been run for this revision.** See [verification status](docs/testing/verification.md) and [deferred test inventory](docs/id/testing.md). Formatting/static inspection and main-source compilation do not establish runtime compatibility.

New components should start with one standalone SDK and a combined starter, with further splits justified by actual framework or release needs. No speculative cache, lock or rate-limit modules exist.

[Documentation](docs/README.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Release process](docs/releasing.md) · [Apache 2.0 license](LICENSE)
