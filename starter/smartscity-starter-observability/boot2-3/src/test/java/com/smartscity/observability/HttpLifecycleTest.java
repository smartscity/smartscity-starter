package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartscity.observability.autoconfigure.ObservabilityProperties;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.http.legacy.ObservationFilter;
import com.smartscity.observability.http.legacy.ObservationRequestListener;
import com.smartscity.observability.logging.EventMarker;
import com.smartscity.observability.logging.JsonBodySanitizer;
import com.smartscity.observability.logging.TextSanitizer;
import com.smartscity.observability.tracing.CorrelationScope;
import java.nio.charset.StandardCharsets;
import javax.servlet.ServletRequestEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies payload transparency, exact terminal emission and reservation cleanup.
 *
 * @author jack
 */
class HttpLifecycleTest {
  @Test
  void capturesWithoutEagerReadAndMasksCompletedBody() throws Exception {
    ObservabilityProperties p = new ObservabilityProperties();
    p.getLogging().getBody().setEnabled(true);
    p.getLogging().getBody().getPaths().add("/test");
    TextSanitizer text = TextSanitizer.defaults();
    ObservationRuntime r =
        new ObservationRuntime(
            p, "test", text, new JsonBodySanitizer(p.getMasking(), text), null, null);
    ObservationFilter filter = new ObservationFilter(r);
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/test");
    byte[] input = "{\"password\":\"private\",\"ok\":1}".getBytes(StandardCharsets.UTF_8);
    req.setContent(input);
    req.setContentType("application/json");
    MockHttpServletResponse res = new MockHttpServletResponse();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    Logger logger = (Logger) LoggerFactory.getLogger("com.smartscity.observability.events");
    logger.addAppender(logs);
    try {
      filter.doFilter(
          req,
          res,
          (request, response) -> {
            assertNotNull(CorrelationScope.current());
            assertArrayEquals(input, request.getInputStream().readAllBytes());
            response.setContentType("application/json");
            response.getOutputStream().write(input);
          });
      new ObservationRequestListener()
          .requestDestroyed(new ServletRequestEvent(req.getServletContext(), req));
      assertArrayEquals(input, res.getContentAsByteArray());
      assertEquals(2, logs.list.size());
      EventMarker end = (EventMarker) logs.list.get(1).getMarker();
      assertEquals("_com_request_out", end.tag());
      assertFalse(end.fields().get("response").contains("private"));
      assertFalse(end.fields().get("request").contains("private"));
      assertEquals("200", end.fields().get("errno"));
      assertEquals(0, r.budget().reservedBytes());
      assertNull(CorrelationScope.current());
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  void writerPreservesCharactersAndMasksAcrossWrites() throws Exception {
    ObservabilityProperties p = new ObservabilityProperties();
    p.getLogging().getBody().setEnabled(true);
    p.getLogging().getBody().getPaths().add("/test");
    TextSanitizer text = TextSanitizer.defaults();
    ObservationRuntime runtime =
        new ObservationRuntime(
            p, "test", text, new JsonBodySanitizer(p.getMasking(), text), null, null);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    Logger logger = (Logger) LoggerFactory.getLogger("com.smartscity.observability.events");
    logger.addAppender(logs);
    String json = "{\"password\":\"private\",\"name\":\"\u4f60\u597d😀\"}";
    try {
      new ObservationFilter(runtime)
          .doFilter(
              request,
              response,
              (a, b) -> {
                b.setCharacterEncoding("UTF-8");
                b.setContentType("application/json");
                java.io.PrintWriter writer = b.getWriter();
                for (int i = 0; i < json.length(); i++) {
                  writer.write(json.charAt(i));
                }
                writer.flush();
              });
      assertEquals(json, response.getContentAsString());
      EventMarker end = (EventMarker) logs.list.get(1).getMarker();
      assertTrue(end.fields().get("response").contains("\u4f60\u597d😀"));
      assertFalse(end.fields().get("response").contains("private"));
      assertEquals(0, runtime.budget().reservedBytes());
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  void clientSkipMakesCaptureIncompleteWithoutChangingStream() throws Exception {
    ObservabilityProperties p = new ObservabilityProperties();
    p.getLogging().getBody().setEnabled(true);
    p.getLogging().getBody().getPaths().add("/test");
    TextSanitizer text = TextSanitizer.defaults();
    ObservationRuntime runtime =
        new ObservationRuntime(
            p, "test", text, new JsonBodySanitizer(p.getMasking(), text), null, null);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    Logger logger = (Logger) LoggerFactory.getLogger("com.smartscity.observability.events");
    logger.addAppender(logs);
    try {
      org.springframework.mock.http.client.MockClientHttpRequest request =
          new org.springframework.mock.http.client.MockClientHttpRequest(
              org.springframework.http.HttpMethod.GET,
              java.net.URI.create("http://localhost/test"));
      org.springframework.mock.http.client.MockClientHttpResponse original =
          new org.springframework.mock.http.client.MockClientHttpResponse(
              " {}".getBytes(StandardCharsets.UTF_8), org.springframework.http.HttpStatus.OK);
      original.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
      org.springframework.http.client.ClientHttpResponse response =
          new com.smartscity.observability.http.ObservationClientInterceptor(runtime)
              .intercept(request, new byte[0], (a, b) -> original);
      assertEquals(1, response.getBody().skip(1));
      assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), response.getBody().readAllBytes());
      response.close();
      assertEquals(1, logs.list.size());
      EventMarker end = (EventMarker) logs.list.get(0).getMarker();
      assertEquals("[omitted:incomplete]", end.fields().get("response"));
      assertEquals("200", end.fields().get("errno"));
      assertEquals(0, runtime.budget().reservedBytes());
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  void applicationErrorReleasesClientCaptureAndPreservesOriginal() throws Exception {
    ObservabilityProperties p = new ObservabilityProperties();
    p.getLogging().getBody().setEnabled(true);
    p.getLogging().getBody().getPaths().add("/test");
    ObservationRuntime runtime =
        new ObservationRuntime(p, "test", TextSanitizer.defaults(), (b, t) -> "safe", null, null);
    org.springframework.mock.http.client.MockClientHttpRequest request =
        new org.springframework.mock.http.client.MockClientHttpRequest(
            org.springframework.http.HttpMethod.POST, java.net.URI.create("http://localhost/test"));
    request.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    AssertionError original = new AssertionError("business error");
    assertSame(
        original,
        assertThrows(
            AssertionError.class,
            () ->
                new com.smartscity.observability.http.ObservationClientInterceptor(runtime)
                    .intercept(
                        request,
                        "{}".getBytes(StandardCharsets.UTF_8),
                        (sent, bytes) -> {
                          assertTrue(runtime.budget().reservedBytes() > 0);
                          throw original;
                        })));
    assertEquals(0, runtime.budget().reservedBytes());
    assertNull(CorrelationScope.current());
  }

  @Test
  void exceptionFallbackDoesNotReplaceException() throws Exception {
    ObservabilityProperties p = new ObservabilityProperties();
    ObservationRuntime r =
        new ObservationRuntime(p, "test", TextSanitizer.defaults(), (b, t) -> "safe", null, null);
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
    MockHttpServletResponse res = new MockHttpServletResponse();
    java.io.IOException original = new java.io.IOException("original");
    assertSame(
        original,
        assertThrows(
            java.io.IOException.class,
            () ->
                new ObservationFilter(r)
                    .doFilter(
                        req,
                        res,
                        (a, b) -> {
                          throw original;
                        })));
    new ObservationRequestListener()
        .requestDestroyed(new ServletRequestEvent(req.getServletContext(), req));
    assertNull(CorrelationScope.current());
    assertEquals(0, r.budget().reservedBytes());
  }
}
