# Logging migration

The unreleased combined `smartscity-observability` / `smartscity-starter-observability` artifacts are replaced by the [three components](../observability/adr/0002-independent-components.md). Remove old coordinates before installing the replacements; never combine old and new JARs or both Boot generations. Existing public Java packages remain unchanged.

Use `com.smartscity:smartscity-starter-logging` for Boot 2.7/3, or its `-boot4` alternative for Boot 4. Existing property keys under `smartscity.observability` remain unchanged.

Logging no longer binds the metrics configuration object or filters native exporters. Code that used `ObservabilityProperties.getMetrics()` must use `MetricsProperties` from the metrics starter. Native Boot exporters explicitly installed in a logging-only application follow Boot behavior; include the metrics component when Smartscity opt-in control is required.
