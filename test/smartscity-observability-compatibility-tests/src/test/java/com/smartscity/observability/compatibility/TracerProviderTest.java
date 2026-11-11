package com.smartscity.observability.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.observability.CorrelationContext;
import com.smartscity.observability.tracing.CorrelationScope;
import com.smartscity.observability.tracing.CorrelationTaskDecorator;
import com.smartscity.observability.tracing.TracerAccess;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Uses real Brave/Micrometer provider objects when the selected consumer profile supplies them.
 *
 * @author jack
 */
class TracerProviderTest {
  @Test
  void nativeBraveAndMicrometerScopesPreserveIds() throws Exception {
    Assumptions.assumeTrue(
        org.springframework.util.ClassUtils.isPresent("brave.Tracing", getClass().getClassLoader()),
        "Provider absent in Boot 2 default consumer");
    Object builder = Class.forName("brave.Tracing").getMethod("newBuilder").invoke(null);
    builder.getClass().getMethod("traceId128Bit", boolean.class).invoke(builder, true);
    Object tracing = builder.getClass().getMethod("build").invoke(builder);
    try {
      Object brave = Class.forName("brave.Tracing").getMethod("tracer").invoke(tracing);
      Object current =
          Class.forName("brave.Tracing").getMethod("currentTraceContext").invoke(tracing);
      exercise(brave, "brave.Tracer");
      Object bridgeContext =
          Class.forName("io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext")
              .getConstructor(Class.forName("brave.propagation.CurrentTraceContext"))
              .newInstance(current);
      Object bridge =
          Class.forName("io.micrometer.tracing.brave.bridge.BraveTracer")
              .getConstructor(
                  Class.forName("brave.Tracer"),
                  Class.forName("io.micrometer.tracing.CurrentTraceContext"))
              .newInstance(brave, bridgeContext);
      exercise(bridge, "io.micrometer.tracing.Tracer");
    } finally {
      Class.forName("brave.Tracing").getMethod("close").invoke(tracing);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void exercise(Object tracer, String type) throws Exception {
    try (GenericApplicationContext app = new GenericApplicationContext()) {
      app.registerBean("provider", (Class) Class.forName(type), () -> tracer);
      Object registry =
          type.equals("io.micrometer.tracing.Tracer")
              ? Class.forName("io.micrometer.observation.ObservationRegistry")
                  .getMethod("create")
                  .invoke(null)
              : null;
      if (registry != null) {
        app.registerBean(
            "observations",
            (Class) Class.forName("io.micrometer.observation.ObservationRegistry"),
            () -> registry);
      }
      app.refresh();
      TracerAccess access = TracerAccess.discover(app);
      assertNotNull(access);
      TracerAccess.Child root = access.child("caller", "service");
      try {
        CorrelationContext parent = root.context();
        clientUnderAmbientParentGetsDistinctChildren(access, parent);
        if (registry != null) {
          nativeObservationOwnsOnlyItsExactClientSpan(access, tracer, registry);
        }
        assertEquals(parent.currentId(), access.current("caller", "service").currentId());
        try (CorrelationScope attached = CorrelationScope.open(parent)) {
          TracerAccess.Child child = access.child("service", "target");
          try {
            assertEquals(parent.currentId(), child.context().parentId());
            assertEquals(parent.traceId(), child.context().traceId());
          } finally {
            child.close();
            child.finish();
          }
          assertEquals(parent.currentId(), access.current("caller", "service").currentId());
          Runnable task =
              new CorrelationTaskDecorator(access)
                  .decorate(
                      () ->
                          assertEquals(
                              parent.currentId(), access.current("caller", "service").currentId()));
          java.util.concurrent.ExecutorService executor =
              java.util.concurrent.Executors.newSingleThreadExecutor();
          try {
            executor.submit(task).get(3, java.util.concurrent.TimeUnit.SECONDS);
          } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
          }
        }
      } finally {
        root.close();
        root.finish();
      }
      assertNull(access.current("caller", "service"));
    }
  }

  private void clientUnderAmbientParentGetsDistinctChildren(
      TracerAccess access, CorrelationContext parent) throws Exception {
    com.smartscity.observability.autoconfigure.ObservabilityProperties properties =
        new com.smartscity.observability.autoconfigure.ObservabilityProperties();
    properties.getLogging().setEnabled(false);
    properties.getCorrelation().getOutboundHosts().add("allowed");
    com.smartscity.observability.http.ObservationRuntime runtime =
        new com.smartscity.observability.http.ObservationRuntime(
            properties,
            "caller",
            com.smartscity.observability.logging.TextSanitizer.defaults(),
            (bytes, type) -> "safe",
            null,
            access);
    com.smartscity.observability.http.ObservationClientInterceptor interceptor =
        new com.smartscity.observability.http.ObservationClientInterceptor(runtime);
    java.util.List<String> children = new java.util.ArrayList<>();
    assertNull(CorrelationScope.current());
    for (int i = 0; i < 2; i++) {
      org.springframework.http.HttpRequest request =
          new org.springframework.http.HttpRequest() {
            private final org.springframework.http.HttpHeaders headers =
                new org.springframework.http.HttpHeaders();

            public org.springframework.http.HttpMethod getMethod() {
              return org.springframework.http.HttpMethod.GET;
            }

            public java.util.Map<String, Object> getAttributes() {
              return java.util.Collections.emptyMap();
            }

            public String getMethodValue() {
              return "GET";
            }

            public java.net.URI getURI() {
              return java.net.URI.create("http://allowed/review");
            }

            public org.springframework.http.HttpHeaders getHeaders() {
              return headers;
            }
          };
      org.springframework.http.client.ClientHttpResponse response =
          interceptor.intercept(
              request,
              new byte[0],
              (sent, bytes) -> {
                CorrelationContext child = CorrelationScope.current();
                assertEquals(parent.currentId(), child.parentId());
                org.junit.jupiter.api.Assertions.assertNotEquals(
                    parent.currentId(), child.currentId());
                assertEquals(parent.traceId(), child.traceId());
                assertEquals(child.traceparent(), sent.getHeaders().getFirst("traceparent"));
                CorrelationContext downstream =
                    CorrelationContext.receive(
                        sent.getHeaders().getFirst("traceparent"), "caller", "downstream");
                assertEquals(child.currentId(), downstream.parentId());
                children.add(child.currentId());
                return (org.springframework.http.client.ClientHttpResponse)
                    java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {org.springframework.http.client.ClientHttpResponse.class},
                        (proxy, method, args) -> {
                          switch (method.getName()) {
                            case "getStatusCode":
                              return org.springframework.http.HttpStatus.OK;
                            case "getHeaders":
                              return new org.springframework.http.HttpHeaders();
                            case "getBody":
                              return new java.io.ByteArrayInputStream(new byte[0]);
                            default:
                              return null;
                          }
                        });
              });
      response.close();
      assertNull(CorrelationScope.current());
      assertEquals(parent.currentId(), access.current("caller", "service").currentId());
    }
    org.junit.jupiter.api.Assertions.assertNotEquals(children.get(0), children.get(1));
  }

  private void nativeObservationOwnsOnlyItsExactClientSpan(
      TracerAccess access, Object tracer, Object registry) throws Exception {
    Class<?> registryType = Class.forName("io.micrometer.observation.ObservationRegistry");
    Class<?> observationType = Class.forName("io.micrometer.observation.Observation");
    Class<?> handlerType = Class.forName("io.micrometer.observation.ObservationHandler");
    Object handler =
        java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {handlerType},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "supportsContext":
                  return true;
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                case "toString":
                  return "review-observation-handler";
                default:
                  return null;
              }
            });
    Object config = registryType.getMethod("observationConfig").invoke(registry);
    Class.forName("io.micrometer.observation.ObservationRegistry$ObservationConfig")
        .getMethod("observationHandler", handlerType)
        .invoke(config, handler);
    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    org.springframework.http.client.ClientHttpRequest request =
        (org.springframework.http.client.ClientHttpRequest)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.springframework.http.client.ClientHttpRequest.class},
                (proxy, method, args) -> {
                  switch (method.getName()) {
                    case "getHeaders":
                      return headers;
                    case "getURI":
                      return java.net.URI.create("http://allowed/review");
                    case "getMethod":
                      return org.springframework.http.HttpMethod.GET;
                    default:
                      return null;
                  }
                });
    Object context =
        Class.forName("org.springframework.http.client.observation.ClientRequestObservationContext")
            .getConstructor(org.springframework.http.client.ClientHttpRequest.class)
            .newInstance(request);
    Class<?> contextType = Class.forName("io.micrometer.observation.Observation$Context");
    Object observation =
        observationType
            .getMethod(
                "createNotStarted", String.class, java.util.function.Supplier.class, registryType)
            .invoke(
                null,
                "http.client.requests",
                (java.util.function.Supplier<Object>) () -> context,
                registry);
    observationType.getMethod("start").invoke(observation);
    Object scope = observationType.getMethod("openScope").invoke(observation);
    TracerAccess.Child nativeChild = access.child("caller", "target");
    try {
      // A metrics-only observation is not proof that the ambient tracer span belongs to it.
      org.junit.jupiter.api.Assertions.assertFalse(access.ownsClientRequest(request));
      Class<?> tracingType =
          Class.forName("io.micrometer.tracing.handler.TracingObservationHandler$TracingContext");
      Object tracing = tracingType.getConstructor().newInstance();
      Object span =
          Class.forName("io.micrometer.tracing.Tracer").getMethod("currentSpan").invoke(tracer);
      tracingType
          .getMethod("setSpan", Class.forName("io.micrometer.tracing.Span"))
          .invoke(tracing, span);
      contextType
          .getMethod("put", Object.class, Object.class)
          .invoke(context, tracingType, tracing);
      assertTrue(access.ownsClientRequest(request));
      org.junit.jupiter.api.Assertions.assertFalse(access.ownsClientRequest(new Object()));
      com.smartscity.observability.autoconfigure.ObservabilityProperties p =
          new com.smartscity.observability.autoconfigure.ObservabilityProperties();
      p.getLogging().setEnabled(false);
      p.getCorrelation().getOutboundHosts().add("allowed");
      com.smartscity.observability.http.ObservationRuntime runtime =
          new com.smartscity.observability.http.ObservationRuntime(
              p,
              "caller",
              com.smartscity.observability.logging.TextSanitizer.defaults(),
              (b, t) -> "safe",
              null,
              access);
      org.springframework.http.client.ClientHttpResponse response =
          new com.smartscity.observability.http.ObservationClientInterceptor(runtime)
              .intercept(
                  request,
                  new byte[0],
                  (sent, bytes) -> {
                    assertEquals(
                        nativeChild.context().currentId(), CorrelationScope.current().currentId());
                    assertEquals(
                        nativeChild.context().traceparent(),
                        sent.getHeaders().getFirst("traceparent"));
                    return (org.springframework.http.client.ClientHttpResponse)
                        java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {
                              org.springframework.http.client.ClientHttpResponse.class
                            },
                            (proxy, method, args) ->
                                method.getName().equals("getStatusCode")
                                    ? org.springframework.http.HttpStatus.OK
                                    : null);
                  });
      response.close();
      assertEquals(
          nativeChild.context().currentId(), access.current("caller", "target").currentId());
    } finally {
      nativeChild.close();
      nativeChild.finish();
      Class.forName("io.micrometer.observation.Observation$Scope").getMethod("close").invoke(scope);
      observationType.getMethod("stop").invoke(observation);
    }
  }
}
