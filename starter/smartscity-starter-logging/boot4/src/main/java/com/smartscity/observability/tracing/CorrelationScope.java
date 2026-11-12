package com.smartscity.observability.tracing;

import com.smartscity.observability.CorrelationContext;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Thread-confined scope preserving previous owned MDC keys and nested context.
 *
 * @author jack
 */
public final class CorrelationScope implements AutoCloseable {
  private static final ThreadLocal<CorrelationContext> CURRENT = new ThreadLocal<>();
  private final Thread owner = Thread.currentThread();
  private final CorrelationContext previous;
  private final Map<String, String> old = new HashMap<>();
  private boolean closed;

  private CorrelationScope(CorrelationContext c) {
    previous = CURRENT.get();
    CURRENT.set(c);
    set("obs.traceid", c == null ? null : c.traceId());
    set("obs.spanid", c == null ? null : c.parentId());
    set("obs.cspanid", c == null ? null : c.currentId());
  }

  public static CorrelationContext current() {
    return CURRENT.get();
  }

  public static CorrelationScope open(CorrelationContext c) {
    return new CorrelationScope(c);
  }

  private void set(String k, String v) {
    old.put(k, MDC.get(k));
    if (v == null) {
      MDC.remove(k);
    } else {
      MDC.put(k, v);
    }
  }

  @Override
  public void close() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("Scope belongs to another thread");
    }
    if (!closed) {
      closed = true;
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
      old.forEach(
          (k, v) -> {
            if (v == null) {
              MDC.remove(k);
            } else {
              MDC.put(k, v);
            }
          });
    }
  }
}
