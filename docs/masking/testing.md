# Masking verification

Current split evidence is recorded in [component verification](../observability/component-split-verification.md). Previous combined-component evidence remains historical and does not certify new packaging.

SDK-only tests cover overlapping AC matches against a randomized oracle, immutable copied policy, shared concurrency, invalid fields/pointers, bounds and absence of Spring/SLF4J.

Artifact verification checks production coordinates, metadata, imports, bytecode and duplicate classes. Benchmarks remain opt-in in the existing observability harness.
