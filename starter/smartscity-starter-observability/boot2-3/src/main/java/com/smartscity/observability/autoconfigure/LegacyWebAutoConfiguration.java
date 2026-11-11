package com.smartscity.observability.autoconfigure;

import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.http.legacy.ObservationFilter;
import com.smartscity.observability.http.legacy.ObservationRequestListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * Class-loading boundary for javax servlet integration.
 *
 * @author jack
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(name = {"javax.servlet.Filter"})
@ConditionalOnMissingClass("org.springframework.http.HttpStatusCode")
@ConditionalOnBean(ObservationRuntime.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LegacyWebAutoConfiguration {
  @Bean
  @Order(100)
  public ObservationFilter smartscityObservationFilter(ObservationRuntime runtime) {
    return new ObservationFilter(runtime);
  }

  @Bean
  public ObservationRequestListener smartscityObservationRequestListener() {
    return new ObservationRequestListener();
  }
}
