# Logging starter usage

Choose `com.smartscity:smartscity-starter-logging:0.1.0-SNAPSHOT` for Boot 2.7/3 or `smartscity-starter-logging-boot4` for Boot 4. The masking SDK is transitive. Supply the web starter for HTTP and Jackson 2 for structured body sanitization. The application owns tracer, appender destinations and executor installation.

The [single complete configuration example and reference](../observability/USER_GUIDE.md) owns existing property keys and the Logback include. A logging-only application uses correlation/logging/masking fields from that example and omits the metrics starter. The optional logging diagnostic bridge honors `metrics.enabled` but creates no registry or sender. Without the metrics component, native Boot exporter dependencies follow the application's normal Boot configuration.

`logging.enabled=false` disables automatic HTTP lifecycle events; use `correlation.enabled=false` separately to disable correlation. The global `smartscity.observability.enabled=false` disables Smartscity assembly. Ordinary appender installation remains explicit.
