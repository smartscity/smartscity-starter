package com.smartscity.observability.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartscity.observability.CorrelationContext;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.logging.EventMarker;
import com.smartscity.observability.metrics.StatsdUdpSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Boots actual embedded web applications against each consumer-selected Boot BOM.
 *
 * @author jack
 */
class ActualApplicationTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    @Bean
    RestTemplate restTemplate() {
      return new RestTemplate();
    }

    @PostMapping("/echo")
    Map<String, Object> echo(@RequestBody Map<String, Object> body) {
      return body;
    }

    @GetMapping("/async")
    java.util.concurrent.Callable<String> async() {
      return () -> "async-ok";
    }
  }

  @Test
  void realHttpPropagationPayloadAndPrometheusScrape() throws Exception {
    SpringApplication app = new SpringApplication(App.class);
    app.setDefaultProperties(
        Map.of(
            "server.port",
            "0",
            "spring.application.name",
            "obs-compat",
            "management.endpoints.web.exposure.include",
            "health,prometheus",
            "smartscity.observability.metrics.prometheus.enabled",
            "true",
            "smartscity.observability.logging.body.enabled",
            "true",
            "smartscity.observability.logging.body.paths",
            "/echo",
            "smartscity.observability.correlation.outbound-hosts",
            "127.0.0.1",
            "logging.level.root",
            "WARN"));
    try (ConfigurableApplicationContext context = app.run()) {
      int port = context.getEnvironment().getProperty("local.server.port", Integer.class);
      Logger logger = (Logger) LoggerFactory.getLogger("com.smartscity.observability.events");
      logger.setLevel(ch.qos.logback.classic.Level.INFO);
      ListAppender<ILoggingEvent> logs = new ListAppender<>();
      logs.list = new java.util.concurrent.CopyOnWriteArrayList<>();
      logs.start();
      logger.addAppender(logs);
      try {
        CorrelationContext caller = CorrelationContext.root("caller");
        HttpURLConnection http =
            (HttpURLConnection)
                URI.create("http://127.0.0.1:" + port + "/echo").toURL().openConnection();
        http.setConnectTimeout(3000);
        http.setReadTimeout(3000);
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        http.setRequestProperty("Content-Type", "application/json");
        http.setRequestProperty("traceparent", caller.traceparent());
        byte[] request =
            "{\"password\":\"keep-business-value\",\"ok\":1}".getBytes(StandardCharsets.UTF_8);
        try (java.io.OutputStream out = http.getOutputStream()) {
          out.write(request);
        }
        assertEquals(200, http.getResponseCode());
        String response = new String(http.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        http.disconnect();
        assertTrue(response.contains("keep-business-value"));
        long completedBy = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (logs.list.size() < 2 && System.nanoTime() < completedBy) {
          Thread.sleep(10);
        }
        java.util.List<EventMarker> events = new java.util.ArrayList<>();
        for (ILoggingEvent e : logs.list) {
          if (e.getMarker() instanceof EventMarker) {
            events.add((EventMarker) e.getMarker());
          }
        }
        assertEquals(2, events.size(), events.toString());
        EventMarker end = events.get(1);
        assertEquals(caller.traceId(), end.fields().get("traceid"));
        assertEquals(caller.currentId(), end.fields().get("spanid"));
        assertNotEquals(caller.currentId(), end.fields().get("cspanid"));
        assertFalse(end.fields().get("response").contains("keep-business-value"));
        assertEquals(0, context.getBean(ObservationRuntime.class).budget().reservedBytes());
        RestTemplate client = context.getBean(RestTemplate.class);
        String async = client.getForObject("http://127.0.0.1:" + port + "/async", String.class);
        assertEquals("async-ok", async);
        context.getBean(MeterRegistry.class).counter("business.orders.accepted").increment();
        String metrics =
            client.getForObject("http://127.0.0.1:" + port + "/actuator/prometheus", String.class);
        assertTrue(metrics.contains("business_orders_accepted"));
        assertTrue(metrics.contains("jvm_memory"));
      } finally {
        logger.detachAppender(logs);
        context
            .getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class)
            .values()
            .forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor::shutdown);
      }
    }
  }

  @Test
  void actualAutoConfigurationStartsOnlyOwnedUdpSender() throws Exception {
    try (DatagramSocket receiver =
        new DatagramSocket(0, java.net.InetAddress.getLoopbackAddress())) {
      receiver.setSoTimeout(5000);
      String prefix =
          org.springframework.boot.SpringBootVersion.getVersion().startsWith("2.")
              ? "management.metrics.export.statsd."
              : "management.statsd.metrics.export.";
      SpringApplication app = new SpringApplication(App.class);
      app.setDefaultProperties(
          Map.of(
              "spring.main.web-application-type",
              "none",
              "spring.application.name",
              "udp-compat",
              "smartscity.observability.metrics.udp.enabled",
              "true",
              prefix + "host",
              "127.0.0.1",
              prefix + "port",
              receiver.getLocalPort(),
              "logging.level.root",
              "WARN"));
      StatsdUdpSender sender;
      try (ConfigurableApplicationContext context = app.run()) {
        sender = context.getBean(StatsdUdpSender.class);
        assertEquals(
            1, context.getBeansOfType(io.micrometer.statsd.StatsdMeterRegistry.class).size());
        context.getBean(MeterRegistry.class).counter("compat.sent", "service", "test").increment();
        boolean seen = false;
        long until = System.nanoTime() + 5_000_000_000L;
        while (!seen && System.nanoTime() < until) {
          byte[] b = new byte[1500];
          DatagramPacket packet = new DatagramPacket(b, b.length);
          receiver.receive(packet);
          seen =
              new String(b, 0, packet.getLength(), StandardCharsets.UTF_8).contains("compat.sent");
        }
        assertTrue(seen);
      }
      assertFalse(sender.isAlive());
    }
  }
}
