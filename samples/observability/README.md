# Observability examples

Boot 3 and Boot 4 consumer applications are grouped here. Build with the root `samples` profile. Run the application main class from an IDE using the [owning configuration example](../../docs/observability/USER_GUIDE.md); the default service name permits a logging-only launch without any UDP sender. Supply an explicit application configuration to opt into StatsD or Prometheus. The receiver is application-owned; this sample never starts a production service.
