package com.smartscity.observability.logging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Bridges scalar and indexed YAML masking settings before Logback starts, without global mutable
 * policy state. Base64 preserves commas and XML-sensitive characters in literal entries.
 *
 * @author jack
 */
public final class LogbackEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 11;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    Binder binder = Binder.get(environment);
    String prefix = "smartscity.observability.";
    Map<String, Object> values = new LinkedHashMap<>();
    values.put(
        "smartscity.observability.internal.logback.literal-credentials",
        binder
            .bind(prefix + "masking.literals", Bindable.listOf(String.class))
            .orElse(java.util.Collections.emptyList())
            .stream()
            .map(v -> Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)))
            .collect(Collectors.joining(",")));
    values.put(
        "smartscity.observability.internal.logback.fields",
        String.join(
            ",",
            binder
                .bind(prefix + "masking.fields", Bindable.listOf(String.class))
                .orElse(java.util.Collections.emptyList())));
    values.put(
        "smartscity.observability.internal.logback.service",
        environment.getProperty(
            prefix + "service-name",
            environment.getProperty("spring.application.name", "unknown")));
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("smartscityObservabilityLogback", values));
  }
}
