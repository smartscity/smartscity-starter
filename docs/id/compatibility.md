# Compatibility

This project does not claim universal Spring Boot 2/3/4 or JDK 9–27 compatibility. Build JDK, bytecode target, upstream supported runtime range and executed runtime tests are separate facts.

## Upstream requirements checked on 2026-10-25

| Upstream baseline | Official Java range | Smartscity artifact |
|---|---|---|
| Boot 2.7.18 | 8–21; Smartscity itself requires Java 9+ bytecode | smartscity-starter-id |
| Boot 3.5.16 | 17–25 | smartscity-starter-id |
| Boot 4.0.9 | 17–27 | smartscity-starter-id-boot4 |

Sources: [Boot 2.7.18](https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/getting-started.html#getting-started.system-requirements), [Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Boot 4.0](https://docs.spring.io/spring-boot/4.0/system-requirements.html). Version compatibility does not imply continued upstream maintenance.

Boot before 2.7 lacks the selected `@AutoConfiguration`/imports baseline and is unsupported. Boot 3/4 cannot run on JDK 9–16. The domain's `--release 9` target does not validate every JVM through 27. Boot 4 adapter and executable samples target Java 17; domain and Boot 2.7/3 adapter target Java 9. Compile all artifacts with JDK 21.

## Evidence

**Current family-layout revision: NOT_RUN.** The latest user instruction stops test execution. The results below belong only to the prior fingerprinted source snapshot.

The historical registry snapshot recorded a passing matrix for Boot **2.7.18, 3.3.6, 3.3.13, 3.4.13, 3.5.16, 4.0.9 and 4.1.2** on each of **Temurin 17.0.0 and 21.0.12.1** (14 combinations, eight checks each, no skipped checks). This verifies these exact combinations, not every patch or JDK within those major lines. The standalone domain probe is reported separately in the historical registry evidence. [Verification status](../testing/verification.md) owns the exact evidence and remaining limits. The application compatibility module selects its own Boot BOM through `boot3`/`boot4` profiles; libraries are installed once at their declared compilation baselines before compatibility execution. Registration, backoff, property binding, absent optional libraries, health/metrics and SpringApplication startup are tested. JDBC-backed startup uses an isolated database; it covers default generation and custom epoch/width generation, safe restart with fresh workers, and rejection of mismatched epoch/width registration.

Historical baseline `f86b741` recorded Boot 2.7.18, 3.3.6, 3.3.13, 3.4.13, 3.5.16, 4.0.9 and 4.1.2 on JDK 21, domain probes on JDK 17/21, MySQL 9.6, PostgreSQL 14.15 and ZooKeeper 3.9.4. These records remain in `test/evidence`; they are not automatically carried forward as current verification.

TiDB, all unexecuted JDK combinations, native images, JPMS and runtime generations absent from the current result table remain NOT_RUN. No empty compatibility project or speculative release line is created.
