package com.smartscity.observability.autoconfigure;

import com.smartscity.observability.metrics.StatsdUdpSender;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.statsd.StatsdConfig;
import io.micrometer.statsd.StatsdFlavor;
import io.micrometer.statsd.StatsdMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Owns an opt-in StatsD registry and a single bounded transport, in destruction dependency order.
 *
 * @author jack
 */
@AutoConfiguration(
    after = ObservabilityAutoConfiguration.class,
    beforeName = {
      "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
    })
@ConditionalOnClass(StatsdMeterRegistry.class)
@ConditionalOnExpression(
    "${smartscity.observability.enabled:true} and ${smartscity.observability.metrics.enabled:true}")
@ConditionalOnProperty(
    prefix = "smartscity.observability.metrics.udp",
    name = "enabled",
    havingValue = "true")
@ConditionalOnMissingBean(StatsdMeterRegistry.class)
public class UdpMetricsAutoConfiguration {
  @Bean
  public StatsdUdpSender statsdUdpSender(Environment e) throws IOException {
    String prefix = prefix(e);
    String host = e.getProperty(prefix + "host");
    Integer port = e.getProperty(prefix + "port", Integer.class);
    if (host == null || port == null) {
      throw new IllegalArgumentException(
          "UDP export requires explicit management StatsD host and port");
    }
    String protocol = e.getProperty(prefix + "protocol", "udp");
    if (!protocol.equalsIgnoreCase("udp")) {
      throw new IllegalArgumentException("Only UDP is supported");
    }
    String nativeEnabled = e.getProperty(prefix + "enabled");
    if ("false".equals(nativeEnabled)) {
      throw new IllegalArgumentException("UDP switch conflicts with disabled native StatsD export");
    }
    return new StatsdUdpSender(
        host,
        port,
        e.getProperty(prefix + "queue-size", Integer.class, 8192),
        e.getProperty(prefix + "max-packet-length", Integer.class, 1400),
        Duration.ofSeconds(2));
  }

  @Bean
  public StatsdMeterRegistry statsdMeterRegistry(StatsdUdpSender sender, Environment e) {
    String prefix = prefix(e);
    String flavor = e.getProperty(prefix + "flavor", "datadog");
    if (!flavor.equalsIgnoreCase("datadog")) {
      throw new IllegalArgumentException("Use datadog flavor with statsd_exporter tag parsing");
    }
    Duration polling =
        org.springframework.boot.context.properties.bind.Binder.get(e)
            .bind(prefix + "polling-frequency", Duration.class)
            .orElse(Duration.ofSeconds(10));
    if (polling.isZero() || polling.isNegative() || polling.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("Invalid StatsD polling interval");
    }
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

          @Override
          public Duration pollingFrequency() {
            return polling;
          }

          @Override
          public boolean enabled() {
            return true;
          }
        };
    StatsdMeterRegistry registry =
        StatsdMeterRegistry.builder(config).clock(Clock.SYSTEM).lineSink(sender).build();
    registry
        .config()
        .meterFilter(
            io.micrometer.core.instrument.config.MeterFilter.maximumAllowableMetrics(10000));
    return registry;
  }

  @Bean
  public io.micrometer.core.instrument.binder.MeterBinder udpDiagnostics(StatsdUdpSender sender) {
    return registry -> {
      FunctionCounter.builder(
              "smartscity.observability.udp.dropped", sender, StatsdUdpSender::droppedLines)
          .register(registry);
      FunctionCounter.builder(
              "smartscity.observability.udp.send.errors", sender, StatsdUdpSender::sendErrors)
          .register(registry);
    };
  }

  private static String prefix(Environment e) {
    String version = org.springframework.boot.SpringBootVersion.getVersion();
    return version != null && version.startsWith("2.")
        ? "management.metrics.export.statsd."
        : "management.statsd.metrics.export.";
  }
}
