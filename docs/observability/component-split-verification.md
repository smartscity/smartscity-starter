# Independent component verification

Status: locally verified, unreleased. ADR-0002 is accepted. Previous CI runs and benchmark reports describe the combined implementation and remain historical.

| Gate | Current status |
|---|---|
| SDK and independent starter contracts | PASS: 66 cases, zero failures or skips |
| SDK public API Javadoc | PASS with the pinned plugin; one upstream Java 9 API module-link warning |
| Artifact metadata, imports, dependency boundaries and duplicate classes | PASS: eight production artifacts, including five observability component binaries |
| Full reactor, sample compilation, dependency convergence and style | PASS locally on JDK 21; opt-in ID integration skips retained |
| Actual Boot 2.7.18 / 3.5.16 / 4.0.9 consumers and statsd_exporter | PASS: 20 passed, one expected Boot 2 native-provider skip across 21 cases |
| All eight SDK/logging/metrics assembly combinations | PASS under each of the three Boot versions |
| New GitHub Actions and benchmark | Pending new validation run; the user explicitly authorized code/archive upload and CI/benchmark execution |

Local raw logs are retained under `test/evidence/component-split-20261112/`. The isolated component contracts contain no framework dependencies in the SDK test classpath; metrics-only tests contain no logging, SDK, Jackson or Logback classes. Logging-only tests have no Smartscity UDP sender class. Existing overlap, escaping, span propagation, request completion, concurrent UDP close and real collector assertions remain in force.

The isolated remote snapshot passes the repository link/layout checker. The shared working tree currently has an unrelated audit-document missing link (`docs/audit/adr/0001-operation-audit.md` to `../testing.md`); that concurrent work is preserved and excluded from this snapshot.

## Architecture review

Masking owns immutable copied rules; invocation scratch state remains local. Logging owns request scopes and capture budgets, and uses the SDK policy without requiring metrics. Metrics owns only its created registry/transport, retaining registry-before-sender destruction and bounded shutdown. Supplied policy and registry backoff is verified. No duplicate class is present across simultaneously usable component JARs. Both generations retain conventional source trees, direct published-parent inheritance and independent registration.

Source comparison also confirms unchanged correlation values, scope/provider adapters, capture state, pipe encoding, literal range union and UDP sender implementation.

No ID production code, allocation semantics, queue algorithm, trace wire protocol or pipe format was changed by this split. SDK API documentation and component migration notes record preserved Java packages and property keys, removed unreleased combined coordinates, and the metrics binding API move. No release, tag, deployment or default-branch merge is included.
