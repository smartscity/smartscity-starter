package com.smartscity.observability.tracing;

import com.smartscity.observability.CorrelationContext;
import org.springframework.context.ApplicationContext;

/**
 * Optional framework adapter using public provider interfaces without domain reflection. This class
 * does not own or close the application's tracer.
 *
 * @author jack
 */
public final class TracerAccess {
  private final Object tracer;
  private final boolean micrometer;
  private final Object observations;

  private TracerAccess(Object tracer, boolean micrometer, Object observations) {
    this.tracer = tracer;
    this.micrometer = micrometer;
    this.observations = observations;
  }

  public static TracerAccess discover(ApplicationContext app) {
    Object micro = bean(app, "io.micrometer.tracing.Tracer");
    if (micro != null) {
      return new TracerAccess(
          micro, true, bean(app, "io.micrometer.observation.ObservationRegistry"));
    }
    Object brave = bean(app, "brave.Tracer");
    return brave == null ? null : new TracerAccess(brave, false, null);
  }

  private static Object bean(ApplicationContext app, String name) {
    try {
      Class<?> c = Class.forName(name, false, app.getClassLoader());
      String[] names = app.getBeanNamesForType(c);
      if (names.length > 1) {
        throw new IllegalStateException("Ambiguous tracer provider: " + name);
      }
      return names.length == 0 ? null : app.getBean(names[0]);
    } catch (ClassNotFoundException e) {
      return null;
    }
  }

  private String type(String micro, String brave) {
    return micrometer ? micro : brave;
  }

  public CorrelationContext current(String caller, String called) {
    Object span =
        invoke(type("io.micrometer.tracing.Tracer", "brave.Tracer"), tracer, "currentSpan");
    return span == null ? null : context(span, caller, called);
  }

  /**
   * Identifies the native observation for this exact HTTP request, not an ambient parent span.
   *
   * @param request current HTTP request carrier
   * @return whether the current observation owns this request
   */
  public boolean ownsClientRequest(Object request) {
    if (observations == null) {
      return false;
    }
    Object observation =
        invoke(
            "io.micrometer.observation.ObservationRegistry", observations, "getCurrentObservation");
    if (observation == null) {
      return false;
    }
    Object context = invoke("io.micrometer.observation.Observation", observation, "getContext");
    try {
      Class<?> type =
          Class.forName(
              "org.springframework.http.client.observation.ClientRequestObservationContext");
      if (!type.isInstance(context) || type.getMethod("getCarrier").invoke(context) != request) {
        return false;
      }
      Class<?> tracingType =
          Class.forName("io.micrometer.tracing.handler.TracingObservationHandler$TracingContext");
      Object tracing =
          Class.forName("io.micrometer.observation.Observation$Context")
              .getMethod("get", Object.class)
              .invoke(context, tracingType);
      if (tracing == null) {
        return false;
      }
      Object owned = tracingType.getMethod("getSpan").invoke(tracing);
      CorrelationContext current = current("unknown", "unknown");
      if (owned == null || current == null) {
        return false;
      }
      CorrelationContext expected = context(owned, "unknown", "unknown");
      return expected.traceId().equals(current.traceId())
          && expected.currentId().equals(current.currentId());
    } catch (ClassNotFoundException e) {
      return false;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot identify HTTP observation", e);
    }
  }

  private CorrelationContext context(Object span, String caller, String called) {
    Object c = invoke(type("io.micrometer.tracing.Span", "brave.Span"), span, "context");
    String name = type("io.micrometer.tracing.TraceContext", "brave.propagation.TraceContext");
    String trace = (String) invoke(name, c, type("traceId", "traceIdString"));
    if (trace.length() == 16) {
      trace = "0000000000000000" + trace;
    }
    String id = (String) invoke(name, c, type("spanId", "spanIdString"));
    String parent = null;
    try {
      parent = (String) invoke(name, c, type("parentId", "parentIdString"));
    } catch (IllegalStateException e) {
      /* Some provider versions do not expose parents. */
    }
    Boolean sampled = (Boolean) invoke(name, c, "sampled");
    return CorrelationContext.existing(
        trace, parent, id, Boolean.TRUE.equals(sampled), caller, called);
  }

  public Child child(String caller, String called) {
    String spanType = type("io.micrometer.tracing.Span", "brave.Span");
    Object span = invoke(type("io.micrometer.tracing.Tracer", "brave.Tracer"), tracer, "nextSpan");
    invoke(spanType, span, "start");
    Object scope;
    try {
      Class<?> s = Class.forName(spanType);
      scope =
          Class.forName(type("io.micrometer.tracing.Tracer", "brave.Tracer"))
              .getMethod("withSpan" + (micrometer ? "" : "InScope"), s)
              .invoke(tracer, span);
    } catch (ReflectiveOperationException e) {
      invoke(spanType, span, type("end", "finish"));
      throw new IllegalStateException("Cannot attach tracer scope", e);
    }
    try {
      return new Child(span, scope, context(span, caller, called));
    } catch (RuntimeException e) {
      try {
        invoke(
            type("io.micrometer.tracing.Tracer$SpanInScope", "brave.Tracer$SpanInScope"),
            scope,
            "close");
      } finally {
        invoke(spanType, span, type("end", "finish"));
      }
      throw e;
    }
  }

  /**
   * One provider-owned child, separating thread-scope restoration from span completion.
   *
   * @author jack
   */
  public final class Child implements AutoCloseable {
    private final Object span;
    private final Object scope;
    private final CorrelationContext context;

    private Child(Object s, Object scope, CorrelationContext c) {
      span = s;
      this.scope = scope;
      context = c;
    }

    public CorrelationContext context() {
      return context;
    }

    private final java.util.concurrent.atomic.AtomicBoolean ended =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Restores the caller thread; span completion is owned by the HTTP exchange. */
    @Override
    public void close() {
      invoke(
          type("io.micrometer.tracing.Tracer$SpanInScope", "brave.Tracer$SpanInScope"),
          scope,
          "close");
    }

    /** Ends a child after response EOF/close or failure, at most once. */
    public void finish() {
      if (ended.compareAndSet(false, true)) {
        invoke(type("io.micrometer.tracing.Span", "brave.Span"), span, type("end", "finish"));
      }
    }
  }

  /**
   * Captures the provider context at submission using its native executor wrapper.
   *
   * @param task task already decorated with Smartscity correlation
   * @return wrapper preserving native provider scope
   */
  public Runnable wrap(Runnable task) {
    if (!micrometer) {
      Object captured = invoke("brave.Tracer", tracer, "currentSpan");
      return () -> {
        Object scope;
        try {
          scope =
              Class.forName("brave.Tracer")
                  .getMethod("withSpanInScope", Class.forName("brave.Span"))
                  .invoke(tracer, captured);
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException("Cannot attach Brave task scope", e);
        }
        try {
          task.run();
        } finally {
          invoke("brave.Tracer$SpanInScope", scope, "close");
        }
      };
    }
    Object current =
        invoke(type("io.micrometer.tracing.Tracer", "brave.Tracer"), tracer, "currentTraceContext");
    try {
      return (Runnable)
          Class.forName(
                  type(
                      "io.micrometer.tracing.CurrentTraceContext",
                      "brave.propagation.CurrentTraceContext"))
              .getMethod("wrap", Runnable.class)
              .invoke(current, task);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unsupported provider task context", e);
    }
  }

  private static Object invoke(String api, Object target, String method) {
    try {
      return Class.forName(api).getMethod(method).invoke(target);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unsupported tracer API: " + api + "." + method, e);
    }
  }
}
