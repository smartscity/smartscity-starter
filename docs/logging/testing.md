# Logging verification

Current split evidence is recorded in [component verification](../observability/component-split-verification.md). Previous combined-component evidence remains historical and does not certify new packaging.

Both generations retain identity/parent propagation, wire escaping, HTTP completion/capture cleanup, early Logback masking and actual-guide binding tests. A logging-only test asserts that the StatsD sender class is absent. Independent consumer tests exercise the combined logging and metrics artifacts.

Artifact verification checks production coordinates, metadata, imports, bytecode and duplicate classes. Benchmarks remain opt-in in the existing observability harness.
