package com.smartscity.observability.compatibility;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smartscity.observability.metrics.StatsdUdpSender;
import io.micrometer.statsd.StatsdConfig;
import io.micrometer.statsd.StatsdFlavor;
import io.micrometer.statsd.StatsdMeterRegistry;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Real owned statsd_exporter process; absent executable is explicitly NOT_RUN/skipped.
 *
 * @author jack
 */
class StatsdExporterTest {
  @Test
  @EnabledIfSystemProperty(named = "observability.test.statsd-exporter", matches = ".+")
  void udpBecomesPrometheusSeries() throws Exception {
    int web, udp;
    try (ServerSocket tcp = new ServerSocket(0);
        DatagramSocket datagram = new DatagramSocket(0)) {
      web = tcp.getLocalPort();
      udp = datagram.getLocalPort();
    }
    Path output = Files.createTempFile("owned-statsd-exporter-", ".log");
    Process process =
        new ProcessBuilder(
                System.getProperty("observability.test.statsd-exporter"),
                "--web.listen-address=127.0.0.1:" + web,
                "--statsd.listen-udp=127.0.0.1:" + udp,
                "--statsd.listen-tcp=",
                "--statsd.parse-dogstatsd-tags")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      long ready = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (true) {
        try {
          scrape(web);
          break;
        } catch (Exception e) {
          if (!process.isAlive() || System.nanoTime() > ready) {
            throw new IllegalStateException(Files.readString(output), e);
          }
          Thread.sleep(30);
        }
      }
      try (StatsdUdpSender sender =
          new StatsdUdpSender("127.0.0.1", udp, 32, 1400, Duration.ofSeconds(1))) {
        StatsdConfig config =
            new StatsdConfig() {
              @Override
              public String get(String k) {
                return null;
              }

              @Override
              public StatsdFlavor flavor() {
                return StatsdFlavor.DATADOG;
              }

              @Override
              public Duration pollingFrequency() {
                return Duration.ofMillis(100);
              }
            };
        StatsdMeterRegistry registry = StatsdMeterRegistry.builder(config).lineSink(sender).build();
        try {
          registry.counter("e2e.accepted", "service", "test").increment(3);
          registry.timer("e2e.elapsed", "service", "test").record(Duration.ofMillis(12));
          java.util.concurrent.atomic.AtomicInteger depth =
              new java.util.concurrent.atomic.AtomicInteger(7);
          registry.gauge(
              "e2e.depth", io.micrometer.core.instrument.Tags.of("service", "test"), depth);
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          String metrics = "";
          while (System.nanoTime() < deadline) {
            metrics = scrape(web);
            if (metrics
                    .lines()
                    .anyMatch(
                        l ->
                            l.startsWith("e2e_accepted")
                                && l.contains("service=\"test\"")
                                && l.endsWith("3"))
                && metrics.contains("e2e_elapsed")
                && metrics
                    .lines()
                    .anyMatch(
                        l ->
                            l.startsWith("e2e_depth{")
                                && l.contains("service=\"test\"")
                                && Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1)) == 7)) {
              return;
            }
            Thread.sleep(30);
          }
          fail("Receiver did not expose expected tagged counter/timer/gauge: " + metrics);
        } finally {
          registry.close();
        }
      }
    } finally {
      process.destroy();
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
      }
    }
  }

  private static String scrape(int port) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection)
            URI.create("http://127.0.0.1:" + port + "/metrics").toURL().openConnection();
    connection.setConnectTimeout(500);
    connection.setReadTimeout(500);
    try {
      return new String(
          connection.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }
}
