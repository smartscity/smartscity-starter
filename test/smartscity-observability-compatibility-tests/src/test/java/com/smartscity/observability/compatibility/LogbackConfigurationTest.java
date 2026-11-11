package com.smartscity.observability.compatibility;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.smartscity.observability.logging.ObservabilityEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Verifies that early Logback startup binds the same indexed masking policy as the starter.
 *
 * @author jack
 */
class LogbackConfigurationTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {}

  @Test
  void emptyDictionaryStartsWithSafeDefaults() {
    SpringApplication application = new SpringApplication(App.class);
    application.setDefaultProperties(
        Map.of(
            "spring.main.web-application-type",
            "none",
            "spring.application.name",
            "defaults",
            "logging.config",
            "classpath:observability-review-logback.xml"));
    try (ConfigurableApplicationContext app = application.run()) {
      Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      ObservabilityEncoder encoder =
          (ObservabilityEncoder)
              ((OutputStreamAppender<?>) root.getAppender("OBSERVABILITY")).getEncoder();
      LoggingEvent event =
          new LoggingEvent(
              getClass().getName(), root, Level.INFO, "password=private; ok=1", null, null);
      event.setMDCPropertyMap(java.util.Collections.emptyMap());
      String output = new String(encoder.encode(event), StandardCharsets.UTF_8);
      assertTrue(encoder.isStarted());
      assertFalse(output.contains("private"));
      assertTrue(output.contains("service=defaults"));
    }
  }

  @Test
  void indexedFieldsAndLiteralsProtectOrdinaryLogs() {
    SpringApplication application = new SpringApplication(App.class);
    application.setDefaultProperties(
        Map.ofEntries(
            Map.entry("spring.main.web-application-type", "none"),
            Map.entry("spring.application.name", "fallback-service"),
            Map.entry("smartscity.observability.service-name", "specific-service"),
            Map.entry("logging.config", "classpath:observability-review-logback.xml"),
            Map.entry("smartscity.observability.masking.fields[0]", "bankAccount"),
            Map.entry("smartscity.observability.masking.literals[0]", "value,with|delimiter"),
            Map.entry("smartscity.observability.logging.max-record-bytes", "1024")));
    try (ConfigurableApplicationContext app = application.run()) {
      Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      OutputStreamAppender<?> appender =
          (OutputStreamAppender<?>) root.getAppender("OBSERVABILITY");
      ObservabilityEncoder encoder = (ObservabilityEncoder) appender.getEncoder();
      LoggingEvent event =
          new LoggingEvent(
              getClass().getName(),
              root,
              Level.INFO,
              "bankAccount=raw-account; note=value,with|delimiter",
              null,
              null);
      event.setMDCPropertyMap(java.util.Collections.emptyMap());
      String encoded = new String(encoder.encode(event), StandardCharsets.UTF_8);
      assertFalse(encoded.contains("raw-account"));
      assertFalse(encoded.contains("value,with"));
      assertTrue(encoded.contains("service=specific-service"));
      event =
          new LoggingEvent(getClass().getName(), root, Level.INFO, "x".repeat(3000), null, null);
      event.setMDCPropertyMap(java.util.Collections.emptyMap());
      assertTrue(encoder.encode(event).length <= 1024);
    }
  }
}
