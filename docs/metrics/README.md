# Metrics starter

Independently integrates Micrometer Prometheus and active StatsD/UDP. It has no dependency on the masking SDK, logging starter, web stack, Jackson or Logback. No application service name is required.

| Owner | Invariant / lifecycle |
|---|---|
| MetricsProperties | Metrics-only binding; exporters opt-in |
| ExporterImportFilter | Prevents unrequested native export; explicit application-native choices remain application-owned |
| UdpMetricsAutoConfiguration | Back off supplied registry; owned registry stops before dependent sender |
| StatsdUdpSender | Bounded ArrayBlockingQueue; non-waiting producer offer; one blocking-idle UDP writer; bounded close |

Boot 2.7/3 use `smartscity-starter-metrics`; Boot 4 uses its alternative `-boot4` binary. Micrometer API and Boot auto-configuration are required dependencies; registry implementations are explicit application dependencies. This keeps unused exporters absent.

The [UDP wire and shutdown contract](../observability/README.md#udp-metrics) remains unchanged. [Usage](USER_GUIDE.md), [migration](migration.md), [compatibility](compatibility.md), [verification](testing.md), [decisions](adr/README.md).
