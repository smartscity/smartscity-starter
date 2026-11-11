# Observability user guide

## Dependencies

Use `com.smartscity:smartscity-starter-observability:0.1.0-SNAPSHOT` for Boot 2.7/3, or `com.smartscity:smartscity-starter-observability-boot4:0.1.0-SNAPSHOT` for Boot 4. Never install both. The standalone `smartscity-observability` artifact contains pure correlation values, masking and encoding.

| Capability | Additional application dependencies |
|---|---|
| Servlet HTTP / managed RestTemplate | Application's Boot web starter |
| JSON body sanitization | `com.fasterxml.jackson.core:jackson-databind` (also on Boot 4, where the application may use Jackson 3 independently) |
| UDP metrics | `io.micrometer:micrometer-registry-statsd`; Actuator for standard Boot JVM/HTTP binders |
| Prometheus HTTP endpoint | Boot Actuator and `io.micrometer:micrometer-registry-prometheus` |
| Existing tracing | Application-owned Micrometer Tracing or Brave; no second tracer is installed |

Use the consuming Boot BOM for external versions. Merely installing an exporter dependency does not authorize an unsolicited Smartscity sender. Explicit native Boot exporter activation remains an application-owned choice when Smartscity does not own that export mode.

## Complete configuration example

This is the single maintained configuration example. It uses Boot 3/4 native StatsD property names. The binding test reads this exact block. The sample sends only when explicitly launched; the receiver must be configured separately.

<!-- configuration-example:start -->
```yaml
spring:
  application:
    name: observability-sample
smartscity:
  observability:
    enabled: true
    correlation:
      enabled: true
      mode: auto
      trusted-callers: []
      outbound-hosts: [localhost, 127.0.0.1]
    logging:
      enabled: true
      max-record-bytes: 16384
      excluded-paths: [/actuator/health, /actuator/prometheus]
      body:
        enabled: false
        paths: [/echo]
        max-bytes: 8192
        total-budget-bytes: 8388608
    masking:
      enabled: true
      fields: [password, token, secret, authorization, cookie]
      paths: [/payment/account]
      literals: example-sensitive-literal
    metrics:
      enabled: true
      udp:
        enabled: true
      prometheus:
        enabled: true
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  statsd:
    metrics:
      export:
        host: 127.0.0.1
        port: 9125
        flavor: datadog
        polling-frequency: 10s
        queue-size: 8192
        max-packet-length: 1400
```
<!-- configuration-example:end -->

| Property (under `smartscity.observability` unless specified) | Default / contract |
|---|---|
| `enabled` | true; controls Smartscity assembly, not application-owned resources |
| `service-name` | Falls back to `spring.application.name`; bounded logical name required for HTTP/correlation |
| `correlation.enabled` | true; unavailable IDs are logged as null when false |
| `correlation.mode` | auto; standalone rejects a detected application tracer |
| `correlation.trusted-callers` | Empty exact-peer IP allowlist; untrusted caller is unknown |
| `correlation.outbound-hosts` | Empty exact-host allowlist for Smartscity header injection |
| `logging.enabled` | true for automatic HTTP lifecycle events; application logging still belongs to the application |
| `logging.excluded-paths` | Exact health/Prometheus paths, excluded from lifecycle events |
| `logging.max-record-bytes` | 16384 UTF-8 bytes including newline; minimum 1024 |
| `logging.body.enabled` | false; enable only with explicit paths |
| `logging.body.paths` | Empty exact-path capture allowlist |
| `logging.body.max-bytes` | 8192 per direction; oversize bodies omitted entirely |
| `logging.body.total-budget-bytes` | 8388608 bytes of simultaneously reserved capture buffers |
| `masking.enabled` | true; disabling it does not enable body capture or disable wire escaping |
| `masking.fields` | Credential names; structured field matching is case-insensitive |
| `masking.paths` | Validated JSON Pointer paths, including array indices; empty pointer masks the whole JSON document; at most 128 paths of 4096 characters each |
| `masking.literals` | Empty; scalar comma-separated values or a YAML list, compiled once; use list entries for literals containing commas |
| `metrics.enabled` | true when dependencies are available; no sender when disabled |
| `metrics.udp.enabled` | false; requires explicit host/port and StatsD dependency |
| `metrics.prometheus.enabled` | false; delegates pull registry/endpoint to Boot |
| Native StatsD `host`, `port` | Required explicitly for UDP; no default localhost export |
| Native StatsD `flavor` | datadog; statsd_exporter must accept DogStatsD tags |
| Native StatsD `protocol` | udp only |
| Native StatsD `polling-frequency` | 10s for polled meters; not a universal event-send period |
| Native StatsD `queue-size`, `max-packet-length` | 8192 lines, 1400-byte datagrams |
| Native StatsD prefix, Boot 2.7 | `management.metrics.export.statsd` |
| Native StatsD prefix, Boot 3/4 | `management.statsd.metrics.export` |

Keep service/instance labels and route sets bounded. No tracing IDs, account IDs, raw URLs or payloads belong in metric tags. UDP is best effort and can lose events; do not use it as an accounting/audit transport. Do not ingest both direct Prometheus and StatsD-derived copies into the same logical series.

## Logback installation

Add an application-owned `logback-spring.xml` with the supplied include and reference its appender:

```xml
<configuration>
  <include resource="com/smartscity/observability/logback.xml"/>
  <root level="INFO"><appender-ref ref="OBSERVABILITY"/></root>
</configuration>
```

The include defines an encoder with pre-Spring safe defaults. An early environment processor binds scalar/indexed YAML fields and literals before Logback starts; it also preserves the explicit service-name override and record-byte limit. Internal bridge properties are implementation details. Configure additional custom encoder settings on an application-owned appender if needed. Preserve application destinations/rolling policies. Without this encoder, lifecycle messages still contain safe pipe records, but the application's existing logger pattern may add its own prefix and ordinary messages do not gain this encoder's protection.

The output format is `[LEVEL][UTC timestamp][source.java:line] _tag||key=value`. Root `spanid` is null; `cspanid` is current. Standard tracing MDC `spanId` retains its standard meaning. The lowercase aliases live under owned `obs.*` MDC keys, which are restored when scopes end. Double pipes inside values are escaped, and exceptions remain one physical line. Source location collection has a cost; no performance claim is made.

Structured headers are not dumped wholesale. Free text masks configured literal values and recognized credential key/value forms. It cannot infer arbitrary secrets. Invalid JSON, invalid UTF-8, excessive nesting and capture/record limits produce omission markers, never raw fallback. Direct stdout, bypass appenders, unsupported logging backends and arbitrary unmanaged threads are outside protection.

## HTTP and async integration

| Boundary | Behavior |
|---|---|
| Servlet request | Typed javax/jakarta adapter selected without requiring both APIs at runtime |
| Request body | Tee consumed bytes only; entry log is metadata; available sanitized body appears on completion |
| Response body | Tee writes without delaying or replacing the real response; binary/compressed/unapproved types omitted |
| RestTemplate beans and Boot-managed RestTemplateBuilder | Automatically receive one interceptor |
| Manually built clients / unmanaged builders | Install `ObservationClientInterceptor` explicitly using the application's `ObservationRuntime` |
| Client completion | EOF/close/failure, not header arrival; abandoned responses cannot produce an honest complete-body event |
| Servlet async/error dispatch | One exchange attribute, completion claim and request-destruction fallback |
| Executor tasks | Compose `CorrelationTaskDecorator` with the application decorator and install it on the owned executor |
| Business outcome | Optional `BusinessResultExtractor` classifies sanitized available content; outer `errno` stays HTTP status |
| External tracer | Reuse one supported provider; ambiguous providers fail configuration; unavailable parent is null |

Application-owned executors, tracers, DataSources and registries are never closed by an observation. The UDP registry depends on its sender so producer shutdown precedes bounded sender drain. The sender has one writing thread and one bounded queue; producer offer may contend briefly on a lock but never waits for capacity or remote delivery. Oversized lines, full/closed queues and locally failed sends increment drop counters. Shutdown uses a queue wake marker and a shared deadline, preserving caller interruption; it never uses an unbounded join. A failed UDP path cannot report every network drop.

## Running examples and verification

The [samples](../../samples/observability/README.md) are normal Maven applications. [Testing](testing.md) records actual verification status and commands. Compatibility claims apply only to the combinations in [compatibility](compatibility.md). The component is unreleased; no benchmark, commit or publication is implied.
