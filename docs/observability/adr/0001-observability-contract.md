# ADR-0001: Observability integration and wire contracts

Status: Accepted on 2026-11-11 by explicit user approval of r0.5 and implementation authorization.

## Decision

Use one starter family with independent correlation, logging, masking and metrics switches. Preserve Boot 2.7/3 and Boot 4 alternative artifacts and framework-independent policy code. Log `spanid` is the parent; `cspanid` is current. Standard tracer IDs retain their native meanings. HTTP propagation uses W3C traceparent. Log presentation is `[LEVEL][time][source] _tag||key=value`, with reversible escaping and bounded masked payloads.

Micrometer StatsD supplies metric semantics and DogStatsD encoding. A bounded queue and one UDP consumer feed statsd_exporter. Producers use offer, the consumer blocks when idle. No business-thread network I/O, unlimited queue, application retransmission, or delivery guarantee. Optional Prometheus pull remains available. Queue and transport lifecycle have one owner.

## Consequences

Servlet APIs are isolated; absent integrations must not break startup. The logging encoder is explicitly installed by the application. Supplied registries, tracing and logging resources remain application-owned. Tests establish actual compatibility; build bytecode alone is not runtime evidence. Benchmarks, commits, push and publication are not authorized by this implementation request.

The [owning design](../README.md) describes failure, masking, resource and acceptance contracts; [usage](../USER_GUIDE.md) owns configuration examples.

## Implementation review clarification (2026-11-11)

The approved wire mapping is unchanged. Public pure-policy `RedactionRanges` makes the existing interval-union rule explicit across literal and credential policies; it owns invocation-local mutable ranges. Native HTTP span reuse requires exact observation/request/span ownership. Early Logback binding must honor the same scalar/indexed field and literal configuration as HTTP sanitization. Capture completion and UDP acceptance/close each retain a single state owner. These are corrections within the authorized first implementation, not new protocols or exporter capabilities. See the [review](../review-20261111.md).
