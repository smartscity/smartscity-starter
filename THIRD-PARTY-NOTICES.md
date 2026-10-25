# Third-party inputs

Smartscity Starter source is licensed under Apache License 2.0; see [LICENSE](LICENSE). Third-party code and dependencies retain their own licenses.

Spring Boot/Spring, SLF4J, optional ZooKeeper/Micrometer and application-supplied JDBC drivers remain external dependencies. Tests use JUnit, AssertJ, H2 and database drivers. JMH 1.37, jopt-simple and Commons Math are confined to the opt-in benchmark artifact; JMH uses GPLv2 with the Classpath Exception.

Legacy reference input was inspected before the original implementation; no legacy source files were copied. Its hashes remain in [the reference manifest](test/evidence/legacy-reference-manifest.json); the original audit is preserved at `f86b741:docs/proposals/id-v1/legacy-input-audit.md` in Git. Dependency license metadata is supplied by each upstream POM. No remote publication has been performed.
