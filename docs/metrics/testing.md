# Metrics verification

Current split evidence is recorded in [component verification](../observability/component-split-verification.md). Previous combined-component evidence remains historical and does not certify new packaging.

Both generations verify a classpath without logging/masking/Jackson/Logback, no service-name requirement, exporter opt-in/disable/backoff, actual-guide binding, real UDP sending, concurrent producers/closers and bounded interrupted shutdown. Independent consumers verify actual Prometheus endpoints and statsd_exporter.

Artifact verification checks production coordinates, metadata, imports, bytecode and duplicate classes. Benchmarks remain opt-in in the existing observability harness.
