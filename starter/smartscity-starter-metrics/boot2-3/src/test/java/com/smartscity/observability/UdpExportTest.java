package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.observability.metrics.StatsdUdpSender;
import io.micrometer.statsd.StatsdConfig;
import io.micrometer.statsd.StatsdFlavor;
import io.micrometer.statsd.StatsdMeterRegistry;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Real owned-loopback UDP evidence, independent of an external metrics service.
 *
 * @author jack
 */
class UdpExportTest {
  @Test
  void realRegistrySendsTaggedLinesThroughOneOwnedSender() throws Exception {
    try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        StatsdUdpSender sender =
            new StatsdUdpSender(
                "127.0.0.1", receiver.getLocalPort(), 32, 1400, Duration.ofSeconds(1))) {
      receiver.setSoTimeout(5000);
      StatsdConfig config =
          new StatsdConfig() {
            @Override
            public String get(String key) {
              return null;
            }

            @Override
            public StatsdFlavor flavor() {
              return StatsdFlavor.DATADOG;
            }
          };
      StatsdMeterRegistry registry = StatsdMeterRegistry.builder(config).lineSink(sender).build();
      try {
        registry.counter("orders.accepted", "service", "test").increment();
        byte[] bytes = new byte[2048];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        receiver.receive(packet);
        String line = new String(bytes, 0, packet.getLength(), StandardCharsets.UTF_8);
        assertTrue(line.contains("orders.accepted"), line);
        assertTrue(line.contains("|c"), line);
        assertTrue(line.contains("service:test"), line);
        assertTrue(packet.getLength() <= 1400);
      } finally {
        registry.close();
      }
    }
  }

  @Test
  void oversizeClosedAndSaturatedQueuesStayBounded() throws Exception {
    try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      StatsdUdpSender sender =
          new StatsdUdpSender("127.0.0.1", receiver.getLocalPort(), 1, 128, Duration.ofMillis(100));
      sender.accept(String.join("", java.util.Collections.nCopies(129, "x")));
      assertEquals(1, sender.droppedLines());
      for (int i = 0; i < 20000; i++) {
        sender.accept("x:1|c");
      }
      assertTrue(sender.queuedLines() <= 1);
      assertTrue(sender.droppedLines() > 1);
      sender.close();
      long before = sender.droppedLines();
      sender.accept("x:1|c");
      assertEquals(before + 1, sender.droppedLines());
      sender.close();
      long end = System.nanoTime() + 1_000_000_000L;
      while (sender.isAlive() && System.nanoTime() < end) {
        Thread.yield();
      }
      assertFalse(sender.isAlive());
    }
  }
}
