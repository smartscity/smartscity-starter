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

## Verification scope

Use the [test commands](testing.md) for the target Boot/JDK/database combination. The independent compatibility consumer selects its own Boot BOM through `boot3`/`boot4`; install production artifacts first. Its assertions cover registration, conditional backoff, binding, absent optional libraries, observers and real application startup. Database checks require an explicitly configured isolated instance.

A successful result applies only to its source snapshot and exact environment. TiDB, native images, JPMS and unexecuted runtime combinations are not verified support targets. Generated logs stay outside version control.
