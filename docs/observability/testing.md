# Observability verification

Status: **PASS for the executed local review scope**, 2026-11-11. Performance benchmarks were subsequently authorized on 2026-11-12; their results are tracked separately. Publication and deployment remain **NOT_RUN**. [Machine-readable review evidence](verification-review-20261111.json) records counts and JAR hashes. Raw logs and XML reports remain in ignored `test/evidence/observability-review-20261111/`.

| Gate | Result | Evidence / practical boundary |
|---|---|---|
| Pure identity, randomized literal overlap oracle, escaping, capture budget | 7 passed | CoreContractTest; no external dependencies |
| Boot 2.7/3 starter policies and HTTP adapters | 20 passed | Includes all 16 capability-switch combinations, missing optional libraries, nested scopes, decoded JSON literals, writers, client skips, ordinary logs and guide binding |
| Boot 4 starter policies and HTTP adapters | 20 passed | Equivalent generation-specific execution, including overlap masking, pointer validation, concurrent close/abort and application Error cleanup |
| Boot 2.7.18 independent application | 5 passed, 1 skipped | Real HTTP, asynchronous response, masking, Prometheus scrape, owned UDP sender and real statsd_exporter; native-provider test skipped because this profile has no provider |
| Boot 3.5.16 independent application | 6 passed | Same tests plus real Logback XML configuration and actual Brave/Micrometer provider scope, client ownership and executor propagation |
| Boot 4.0.9 independent application | 6 passed | Same tests plus real Logback XML configuration and actual Brave/Micrometer provider scope, client ownership and executor propagation |
| Real receiver, statsd_exporter 0.28.0 | PASS in all three consumers | UDP Counter/Timer/Gauge become tagged Prometheus series; tests create and terminate their own process |
| Artifact boundaries | PASS | Six production JARs, bytecode, zero domain dependencies, optional adapters, metadata, imports registration and production-only BOM |
| Repository / documentation | PASS | English source, links, conventional Maven roots and shared observability source parity |
| Dependency convergence / style | PASS | Pinned Maven Enforcer and Spotless validation |
| Javadoc | PASS | Three observability artifacts, doclint all,-missing |
| Samples | PASS build | Boot 3/4 samples included in the reactor build; no deployment implied |
| Existing ID regression | 167 passed, 33 skipped, 0 failures/errors | 200 discovered cases in the existing reactor; skipped cases remain unverified |
| Performance / remote infrastructure | IN_PROGRESS | Authorized functional/runtime matrix and measured benchmark; see [experiment contract](benchmark.md). No production capacity claim |

The observability total is **64 passed, 1 skipped, 0 failures/errors**. These are test cases, not a count of all assertions or switch permutations. Runtime verification uses JDK 21.0.12+1.1-tem and Maven Wrapper 3.9.15 on macOS. Bytecode does not prove a wider JVM runtime matrix.

The final Boot 2.7 HTTP test logs a Tomcat AsynchronousCloseException while its owned listener shuts down. Its assertions and process exit pass. The task executor is shut down by the test; UDP sender termination is asserted. This shutdown log is not suppressed or presented as an application request failure.

## Commands and scope

Build/install the observability production modules before running independent consumers:

```sh
./mvnw -pl module/smartscity-observability,starter/smartscity-starter-observability/boot2-3,starter/smartscity-starter-observability/boot4 -am install javadoc:javadoc -Ddoclint=all,-missing
```

Run each independent consumer, keeping its reports before the next profile overwrites them:

```sh
./mvnw -f test/smartscity-observability-compatibility-tests/pom.xml test -Dobservability.test.statsd-exporter=/absolute/path/to/statsd_exporter
./mvnw -f test/smartscity-observability-compatibility-tests/pom.xml -Pboot3 test -Dobservability.test.statsd-exporter=/absolute/path/to/statsd_exporter
./mvnw -f test/smartscity-observability-compatibility-tests/pom.xml -Pboot4 test -Dobservability.test.statsd-exporter=/absolute/path/to/statsd_exporter
```

The receiver test is explicitly skipped when that executable property is absent. The executed binary was the official Darwin/amd64 0.28.0 release; archive SHA-256 is recorded in the evidence JSON. The tests bind owned ephemeral loopback ports and terminate only their own receiver process. No database or production collector is contacted by observability tests.

The earlier implementation snapshot ran the broader repository regression and samples with (these unrelated regressions were not repeated for the focused review):

```sh
./mvnw -Pverification,samples -pl '!build/smartscity-dependencies,!test/smartscity-observability-compatibility-tests' verify
python3 test/check-artifacts.py
python3 test/check-repository.py
```

The observability consumer is excluded from that reactor invocation because it is exercised independently against each Boot BOM. Formatting is applied only where needed before verification. The final changed observability modules and samples also passed `validate` after packaging cleanup.

## Remaining boundaries

Boot 2.7 plus Sleuth, WebFlux/WebClient, Feign, alternate log backends, native images and the broader JVM matrix are NOT_RUN/unsupported as described in compatibility. Tests do not establish UDP reliability, sustained throughput or compressed storage savings. Existing application-native tracing/metrics resource ownership is preserved by conditional integration; explicitly managed application resources still follow the application's own shutdown lifecycle.

See the [review findings](review-20261111.md). The [initial 46-pass snapshot](verification-20261111.json) is historical and was superseded by the targeted review regressions.
