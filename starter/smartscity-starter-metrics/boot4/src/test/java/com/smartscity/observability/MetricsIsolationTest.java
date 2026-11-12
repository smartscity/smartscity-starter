package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.observability.autoconfigure.ExporterImportFilter;
import com.smartscity.observability.autoconfigure.MetricsAutoConfiguration;
import com.smartscity.observability.autoconfigure.MetricsProperties;
import com.smartscity.observability.autoconfigure.UdpMetricsAutoConfiguration;
import com.smartscity.observability.metrics.StatsdUdpSender;
import io.micrometer.statsd.StatsdConfig;
import io.micrometer.statsd.StatsdMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.ClassUtils;

/**
 * Verifies an independent metrics classpath, opt-in, ownership, backoff and documentation binding.
 *
 * @author jack
 */
class MetricsIsolationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  MetricsAutoConfiguration.class, UdpMetricsAutoConfiguration.class));

  private ApplicationContextRunner udp() {
    String prefix =
        org.springframework.boot.SpringBootVersion.getVersion().startsWith("2.")
            ? "management.metrics.export.statsd."
            : "management.statsd.metrics.export.";
    return runner.withPropertyValues(
        "smartscity.observability.metrics.udp.enabled=true",
        prefix + "host=127.0.0.1",
        prefix + "port=9125");
  }

  @Test
  void metricsAloneNeedsNoServiceNameOrLoggingClasses() {
    runner.run(
        c -> {
          assertNull(c.getStartupFailure());
          assertNotNull(c.getBean(MetricsProperties.class));
          assertTrue(c.getBeansOfType(StatsdUdpSender.class).isEmpty());
          for (String name :
              new String[] {
                "com.smartscity.observability.http.ObservationRuntime",
                "com.smartscity.observability.logging.TextSanitizer",
                "ch.qos.logback.classic.Logger",
                "com.fasterxml.jackson.databind.ObjectMapper"
              }) {
            assertFalse(ClassUtils.isPresent(name, c.getClassLoader()), name);
          }
        });
  }

  @Test
  void udpOptInStopsOwnedWorkerOnContextClose() {
    AtomicReference<StatsdUdpSender> owned = new AtomicReference<>();
    udp()
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              StatsdUdpSender sender = c.getBean(StatsdUdpSender.class);
              owned.set(sender);
              assertTrue(sender.isAlive());
              assertEquals(1, c.getBeansOfType(StatsdMeterRegistry.class).size());
              c.getBean(StatsdMeterRegistry.class).counter("isolation.counter").increment();
            });
    assertFalse(owned.get().isAlive());
    assertEquals(0, owned.get().queuedLines());
  }

  @Test
  void disabledOrMissingExporterDoesNotCreateTransport() {
    for (String switchName :
        new String[] {
          "smartscity.observability.enabled", "smartscity.observability.metrics.enabled"
        }) {
      udp()
          .withPropertyValues(switchName + "=false")
          .run(
              c -> {
                assertNull(c.getStartupFailure());
                assertTrue(c.getBeansOfType(StatsdUdpSender.class).isEmpty());
              });
    }
    udp()
        .withClassLoader(new FilteredClassLoader("io.micrometer.statsd"))
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              assertTrue(c.getBeansOfType(StatsdUdpSender.class).isEmpty());
            });
    runner
        .withPropertyValues("smartscity.observability.metrics.udp.enabled=true")
        .run(c -> assertNotNull(c.getStartupFailure()));
  }

  @Test
  void suppliedRegistryPreventsOwnedTransport() {
    udp()
        .withBean(
            StatsdMeterRegistry.class,
            () ->
                StatsdMeterRegistry.builder(
                        new StatsdConfig() {
                          public String get(String key) {
                            return null;
                          }
                        })
                    .lineSink(line -> {})
                    .build())
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              assertTrue(c.getBeansOfType(StatsdUdpSender.class).isEmpty());
              assertEquals(1, c.getBeansOfType(StatsdMeterRegistry.class).size());
            });
  }

  @Test
  void exporterFilterSeparatesOwnedAndApplicationNativeActivation() {
    ExporterImportFilter filter = new ExporterImportFilter();
    String[] names = {
      "test.StatsdMetricsExportAutoConfiguration", "test.PrometheusMetricsExportAutoConfiguration"
    };
    filter.setEnvironment(new MockEnvironment());
    assertFalse(filter.match(names, null)[0]);
    assertFalse(filter.match(names, null)[1]);
    filter.setEnvironment(
        new MockEnvironment()
            .withProperty("smartscity.observability.metrics.prometheus.enabled", "true")
            .withProperty("smartscity.observability.metrics.udp.enabled", "true"));
    assertFalse(filter.match(names, null)[0]);
    assertTrue(filter.match(names, null)[1]);
    filter.setEnvironment(
        new MockEnvironment()
            .withProperty("smartscity.observability.enabled", "false")
            .withProperty("management.statsd.metrics.export.enabled", "true")
            .withProperty("management.prometheus.metrics.export.enabled", "true"));
    assertTrue(filter.match(names, null)[0]);
    assertTrue(filter.match(names, null)[1]);
  }

  @Test
  void bindsMetricsFromActualSharedGuide() throws Exception {
    Path root = java.nio.file.Paths.get("").toAbsolutePath();
    while (root != null && !Files.exists(root.resolve("docs/observability/USER_GUIDE.md"))) {
      root = root.getParent();
    }
    assertNotNull(root);
    String guide =
        new String(
            Files.readAllBytes(root.resolve("docs/observability/USER_GUIDE.md")),
            java.nio.charset.StandardCharsets.UTF_8);
    String yaml =
        guide
            .split("<!-- configuration-example:start -->", 2)[1]
            .split("```yaml", 2)[1]
            .split("```", 2)[0];
    StandardEnvironment env = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load(
            "guide", new ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .forEach(source -> env.getPropertySources().addFirst(source));
    MetricsProperties value =
        Binder.get(env).bind("smartscity.observability.metrics", MetricsProperties.class).get();
    assertTrue(value.isEnabled());
    assertTrue(value.getUdp().isEnabled());
    assertTrue(value.getPrometheus().isEnabled());
  }
}
