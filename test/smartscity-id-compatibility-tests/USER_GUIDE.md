# Compatibility test usage

Install production artifacts on JDK 21 first with `./mvnw install`. Run this independent consumer with `./mvnw -f test/smartscity-id-compatibility-tests/pom.xml test`. Select `-Pboot3` or `-Pboot4` for another generation, `-Dspring-boot.version=<version>` for an exact release, and `-Djvm=/path/to/java` for the test JVM. Maven itself still runs on JDK 21.

Without `-Did.test.mysql.url=...`, real database cases are skipped. Only use an owned isolated MySQL database initialized with the adapter schema; the fixture uses the local test root account. Tests verify published imports, backoff, optional-library absence, binding and configuration isolation, SpringApplication startup/close, and database-backed custom-format generation/restart/mismatch rejection.

The [test inventory](../../docs/id/testing.md) owns commands for separate fault and standalone runtime gates. [Compatibility](../../docs/id/compatibility.md) records verified combinations; a successful compilation is not runtime proof. Test artifacts are never production dependencies.
