# Logging starter

Owns correlation, HTTP entry/completion, capture budgets and the approved pipe encoder. Depends on the [masking SDK](../masking/README.md); it does not depend on the metrics starter or a StatsD registry.

| Owner | Invariant / lifecycle |
|---|---|
| CorrelationContext / CorrelationScope | Immutable IDs; thread-owned MDC restoration; parent `spanid`, current `cspanid` |
| ObservationRuntime / ExchangeObservation | Application capture budget; one terminal outcome and release per exchange |
| JsonBodySanitizer | Adapts immutable SDK policy through optional Jackson 2; omit incomplete/invalid bodies |
| ObservabilityEncoder | Application-owned destination; sanitization before pipe escaping |
| DiagnosticMetricsAutoConfiguration | Optional bridge to an existing MeterRegistry; never installs exporters |

Two alternative combined integration/starter binaries: `smartscity-starter-logging` and `smartscity-starter-logging-boot4`. Pure logging values and codecs live here, with no extra utility artifact. [Identity, wire and lifecycle details](../observability/README.md) remain the shared behavior contract.

[Usage](USER_GUIDE.md), [migration](migration.md), [compatibility](compatibility.md), [verification](testing.md), [decisions](adr/README.md).
