package com.smartscity.observability.autoconfigure;

import com.smartscity.observability.http.ObservationClientInterceptor;
import com.smartscity.observability.http.ObservationRuntime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Integrates application-managed builders as well as directly declared client beans.
 *
 * @author jack
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnBean(ObservationRuntime.class)
@ConditionalOnClass(name = "org.springframework.boot.web.client.RestTemplateCustomizer")
public class RestTemplateBuilderAutoConfiguration {
  @Bean
  public RestTemplateCustomizer observationClientCustomizer(ObservationRuntime runtime) {
    return client -> {
      if (client.getInterceptors().stream()
          .noneMatch(i -> i instanceof ObservationClientInterceptor)) {
        client.getInterceptors().add(new ObservationClientInterceptor(runtime));
      }
    };
  }
}
