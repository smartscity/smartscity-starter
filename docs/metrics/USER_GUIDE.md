# Metrics starter usage

Choose `com.smartscity:smartscity-starter-metrics:0.1.0-SNAPSHOT` for Boot 2.7/3 or `smartscity-starter-metrics-boot4` for Boot 4. Add `micrometer-registry-statsd` for UDP; add Actuator and `micrometer-registry-prometheus` for a Prometheus endpoint. External versions belong to the application's Boot BOM.

Use the metrics/management sections of the [single configuration example and property reference](../observability/USER_GUIDE.md). The logging and masking sections are unnecessary in a metrics-only application. Both exporters default off. UDP requires explicit host and port; native StatsD keys differ between Boot 2 and Boot 3/4 as documented there.

Business code records counters/timers through its `MeterRegistry`; Micrometer encodes events and polls gauges. Smartscity transports complete lines through one bounded queue and one UDP writer to statsd_exporter. Overflow increments local drops. Queue acceptance does not establish delivery. Keep meter names/tags bounded and avoid double-counting direct and StatsD-derived Prometheus series.
