package com.smartscity.observability.autoconfigure;

import com.smartscity.observability.http.ObservationClientInterceptor;
import com.smartscity.observability.http.ObservationRuntime;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Installs exactly one interceptor on application-managed RestTemplate beans.
 *
 * @author jack
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnBean(ObservationRuntime.class)
@ConditionalOnClass(RestTemplate.class)
public class RestTemplateAutoConfiguration {
  @Bean
  public static BeanPostProcessor observationRestTemplates(
      org.springframework.beans.factory.ObjectProvider<ObservationRuntime> runtime) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof RestTemplate) {
          ObservationRuntime r = runtime.getIfAvailable();
          RestTemplate t = (RestTemplate) bean;
          if (r != null
              && t.getInterceptors().stream()
                  .noneMatch(i -> i instanceof ObservationClientInterceptor)) {
            t.getInterceptors().add(new ObservationClientInterceptor(r));
          }
        }
        return bean;
      }
    };
  }
}
