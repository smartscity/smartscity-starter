package com.smartscity.observability.autoconfigure;

import com.smartscity.observability.http.BusinessResultExtractor;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.logging.JsonBodySanitizer;
import com.smartscity.observability.logging.StructuredSanitizer;
import com.smartscity.observability.logging.TextSanitizer;
import com.smartscity.observability.tracing.CorrelationTaskDecorator;
import com.smartscity.observability.tracing.TracerAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Composition root; policies are immutable after validation and resources have explicit owners.
 *
 * @author jack
 */
@AutoConfiguration
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnProperty(
    prefix = "smartscity.observability",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ObservabilityAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public TextSanitizer observationTextSanitizer(ObservabilityProperties p) {
    validate(p);
    return new TextSanitizer(
        p.getMasking().isEnabled(), p.getMasking().getLiterals(), p.getMasking().getFields());
  }

  @Bean
  @ConditionalOnMissingBean
  public StructuredSanitizer observationBodySanitizer(
      ObservabilityProperties p, TextSanitizer text, ApplicationContext app) {
    if (ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper", app.getClassLoader())) {
      return new JsonBodySanitizer(p.getMasking(), text);
    }
    return (bytes, type) -> "[omitted:json-library-absent]";
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnExpression(
      "${smartscity.observability.correlation.enabled:true} or ${smartscity.observability.logging.enabled:true}")
  public ObservationRuntime observationRuntime(
      ObservabilityProperties p,
      Environment env,
      TextSanitizer text,
      StructuredSanitizer body,
      ObjectProvider<BusinessResultExtractor> extractor,
      ApplicationContext app) {
    String service = p.getServiceName();
    if (service == null) {
      service = env.getProperty("spring.application.name");
    }
    if (service == null || !service.matches("[A-Za-z0-9_.-]{1,128}")) {
      throw new IllegalArgumentException(
          "Observability requires a bounded service-name or spring.application.name");
    }
    TracerAccess tracer = p.getCorrelation().isEnabled() ? TracerAccess.discover(app) : null;
    if (!p.getCorrelation().getMode().equals("auto")
        && !p.getCorrelation().getMode().equals("standalone")) {
      throw new IllegalArgumentException("Unsupported correlation mode");
    }
    if (tracer != null && p.getCorrelation().getMode().equals("standalone")) {
      throw new IllegalArgumentException(
          "Standalone correlation conflicts with existing tracing provider");
    }
    return new ObservationRuntime(p, service, text, body, extractor.getIfAvailable(), tracer);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "smartscity.observability.correlation",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public CorrelationTaskDecorator correlationTaskDecorator(ApplicationContext app) {
    return new CorrelationTaskDecorator(TracerAccess.discover(app));
  }

  private static void validate(ObservabilityProperties p) {
    int bytes = p.getLogging().getBody().getMaxBytes();
    if (bytes < 1 || bytes > 1_048_576 || p.getLogging().getBody().getTotalBudgetBytes() < bytes) {
      throw new IllegalArgumentException("Invalid body capture limits");
    }
    new com.smartscity.observability.PipeLogEncoder(p.getLogging().getMaxRecordBytes());
    if (p.getLogging().getBody().isEnabled() && p.getLogging().getBody().getPaths().isEmpty()) {
      throw new IllegalArgumentException("Body capture requires explicit paths");
    }
  }
}
