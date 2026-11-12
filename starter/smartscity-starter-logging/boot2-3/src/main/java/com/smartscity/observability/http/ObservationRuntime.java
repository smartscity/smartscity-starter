package com.smartscity.observability.http;

import com.smartscity.observability.CaptureBudget;
import com.smartscity.observability.CorrelationContext;
import com.smartscity.observability.PipeLogEncoder;
import com.smartscity.observability.autoconfigure.ObservabilityProperties;
import com.smartscity.observability.logging.EventMarker;
import com.smartscity.observability.logging.StructuredSanitizer;
import com.smartscity.observability.logging.TextSanitizer;
import com.smartscity.observability.tracing.CorrelationScope;
import com.smartscity.observability.tracing.TracerAccess;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition collaborator for HTTP adapters; per-exchange mutable state lives in
 * ExchangeObservation.
 *
 * @author jack
 */
public final class ObservationRuntime {
  private static final Logger LOG = LoggerFactory.getLogger("com.smartscity.observability.events");
  private final ObservabilityProperties properties;
  private final String service;
  private final TextSanitizer text;
  private final StructuredSanitizer body;
  private final BusinessResultExtractor result;
  private final TracerAccess tracer;
  private final PipeLogEncoder encoder;
  private final CaptureBudget budget;
  private final AtomicLong failures = new AtomicLong();

  public ObservationRuntime(
      ObservabilityProperties p,
      String service,
      TextSanitizer text,
      StructuredSanitizer body,
      BusinessResultExtractor result,
      TracerAccess tracer) {
    this.properties = p;
    this.service = service;
    this.text = text;
    this.body = body;
    this.result = result;
    this.tracer = tracer;
    encoder = new PipeLogEncoder(p.getLogging().getMaxRecordBytes());
    budget = new CaptureBudget(p.getLogging().getBody().getTotalBudgetBytes());
  }

  public ObservabilityProperties properties() {
    return properties;
  }

  public String service() {
    return service;
  }

  public TracerAccess tracer() {
    return tracer;
  }

  public void recordFailure() {
    failures.incrementAndGet();
  }

  public long failures() {
    return failures.get();
  }

  public CaptureBudget budget() {
    return budget;
  }

  public CorrelationContext server(String header, String caller, String peer) {
    if (!properties.getCorrelation().isEnabled()) {
      return null;
    }
    String trusted =
        properties.getCorrelation().getTrustedCallers().contains(peer)
                && caller != null
                && caller.matches("[A-Za-z0-9_.-]{1,128}")
            ? caller
            : "unknown";
    if (tracer != null) {
      try {
        return tracer.current(trusted, service);
      } catch (RuntimeException e) {
        failures.incrementAndGet();
        return null;
      }
    }
    return CorrelationContext.receive(header, trusted, service);
  }

  public CorrelationContext client(String target) {
    if (!properties.getCorrelation().isEnabled()) {
      return null;
    }
    CorrelationContext parent = CorrelationScope.current();
    if (parent != null) {
      return parent.child(service, target);
    }
    CorrelationContext root = CorrelationContext.root(service);
    return CorrelationContext.existing(
        root.traceId(), null, root.currentId(), false, service, target);
  }

  public boolean logs(String path) {
    return LOG.isInfoEnabled()
        && properties.getLogging().isEnabled()
        && !properties.getLogging().getExcludedPaths().contains(path);
  }

  /**
   * Checks path-level capture opt-in before installing body tees.
   *
   * @param path exact application path
   * @return whether the path may reserve body storage
   */
  public boolean captures(String path) {
    return properties.getLogging().getBody().isEnabled()
        && properties.getLogging().getBody().getPaths().contains(path)
        && logs(path);
  }

  public CaptureBudget.Capture capture(String path, String type, String encoding) {
    if (!captures(path) || encoding != null && !encoding.isEmpty() || type == null) {
      return null;
    }
    String t = type.toLowerCase(java.util.Locale.ROOT);
    if (!(t.startsWith("application/json")
        || t.split(";", 2)[0].endsWith("+json")
        || t.startsWith("application/x-www-form-urlencoded"))) {
      return null;
    }
    return budget.reserve(properties.getLogging().getBody().getMaxBytes());
  }

  public String body(CaptureBudget.Capture c, String type, boolean complete) {
    if (c == null) {
      return null;
    }
    if (!complete) {
      return "[omitted:incomplete]";
    }
    try {
      return body.sanitize(c.snapshot(), type);
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      return "[omitted:sanitize-failure]";
    }
  }

  public Boolean result(int status, String payload) {
    if (status >= 400) {
      return false;
    }
    if (status < 200 || status >= 400) {
      return null;
    }
    if (result == null) {
      return true;
    }
    try {
      return result.success(status, payload);
    } catch (RuntimeException e) {
      failures.incrementAndGet();
      return null;
    }
  }

  public void emit(String tag, CorrelationContext context, Map<String, String> values) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    try {
      StackTraceElement source = new Throwable().getStackTrace()[0];
      Map<String, String> all = new LinkedHashMap<>();
      all.put("traceid", context == null ? null : context.traceId());
      all.put("spanid", context == null ? null : context.parentId());
      all.put("cspanid", context == null ? null : context.currentId());
      all.put("hintcode", "0");
      all.put("service", service);
      values.forEach((k, v) -> all.put(k, text.sanitize(v)));
      EventMarker marker = new EventMarker(tag, all);
      LOG.info(
          marker,
          encoder
              .encode(
                  "INFO",
                  Instant.now(),
                  source.getFileName() + ":" + source.getLineNumber(),
                  tag,
                  all)
              .trim());
    } catch (RuntimeException e) {
      failures.incrementAndGet();
    }
  }
}
