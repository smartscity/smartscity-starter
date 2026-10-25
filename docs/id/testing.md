# Test inventory

| Owner | Tests / gate | Boundary |
|---|---|---|
| ID domain | IdExceptionTest | Stable reason codes, default messages, details and original cause |
| ID domain | CoreTest, ClockAndLifecycleTest, IdLayoutTest | Default/custom immutable formats, field/epoch bounds, multi-worker concurrency, CAS, capacity, timeout, interrupt, producer and cleanup |
| ID domain | BufferedRangeTest | Partial range publication, full/wrapped rings, unpublished suffix retention, zero/sequence/timestamp boundaries and concurrent payload visibility across many laps |
| ID domain | ClockRollbackTest, ClockRollbackConcurrencyTest, HotPathCorrectnessTest | Producer-detected rollback/capacity failures, no consumer clock samples, pre-detection cached issuance, sample retry/overflow, published-failure burn and terminal races |
| ID domain | RestartConcurrencyTest | Opt-in bounded concurrent restarts; no soak |
| Both starter generations | AutoConfigurationTest | Disable, backoff, default/custom binding, application isolation, invalid formats, health, metrics |
| Both starter generations | ClockValidationTest, LifecycleReviewTest | Calibration, overflow/NULL, cancellation, late JDBC cleanup |
| Both starter generations | JdbcIntegrationTest | H2 concurrent bounds, duplicate instance, exhaustion/non-reuse, token rejection, rollback, unknown commit and NULL epoch; the same registry assertions also run on real MySQL/PostgreSQL |
| Both starter generations | MySqlIntegrationTest, PostgresIntegrationTest | Real database schema, clock, conflicts, multiple processes and shutdown |
| Both starter generations | ZookeeperIntegrationTest | Temporary server and TCP clients, disconnect/reconnect/expiry/close |
| Starter fault run | MySqlOutageTest | Explicit isolated database shutdown after readiness |
| Starter restart run | RestartStabilityTest, ZookeeperRestartStabilityTest | Bounded restart/flapping; explicit property gate |
| Cross-module | StarterDiscoveryTest | Published imports, user bean, disable, optional-library absence, binding, actual SpringApplication startup and optional MySQL allocation |
| Standalone | CoreRuntimeProbe | Same domain JAR, five layouts, target JVM execution |
| Packaging | test/check-artifacts.py | Bytecode, framework isolation, production-only BOM, metadata and starter contents |
| Benchmarks | IdBenchmark, FixedRateProbe, check-benchmark-ci.sh | Existing JMH and uniqueness harness; opt-in measurements; synthetic orchestration checks |

Use fresh reports for the source and environment under verification. Skipped, interrupted and unexecuted checks are not passing results. Local output under `test/evidence/` is ignored by Git; retain it externally when needed.

`DocumentationConfigurationTest` in each starter reads the actual marked YAML in `docs/id/USER_GUIDE.md`. It checks sample equality, scalar binding, defaults, environment/property precedence, explicit-width precedence, incomplete/invalid fields and required database selection. The two standard test trees are synchronized by the repository layout gate.

Run normal gates with `./mvnw -Pverification,samples verify`. Real database tests use `id.test.mysql.url` / `id.test.postgres.url`; no supplied URL means skipped, never PASS. ZooKeeper tests start private temporary servers. Run `python3 test/run-destructive.py --run --restart --output <new-directory>` only against its owned loopback instance; it rejects occupied ports, kills only owned child process groups, and records cleanup. Never modify host time; clocks are injected. A paused process is not a physical machine suspend test.

For compatibility, first install production artifacts, then run `./mvnw -f test/smartscity-id-compatibility-tests/pom.xml test`, repeat with `-Pboot3` and `-Pboot4`. Override `spring-boot.version` only on this independent consumer project, not the whole reactor. Use `-Djvm=/path/to/other/jdk/bin/java` to run Surefire on another installed JDK while Maven stays on build JDK 21.

For the owned local MySQL matrix and packaged samples, run `python3 test/run-compatibility.py --output <new-directory> --java <runtime-jdk-home> [--java <another-jdk-home>]` with build `JAVA_HOME` set to JDK 21 and `mysqld`/`mysql` on PATH. It creates a new private data directory, checks each eight-test result for skips/failures, and verifies cleanup.
