package com.smartscity.observability.autoconfigure;

import com.smartscity.observability.http.ObservationRuntime;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;

/**
 * Registers finite diagnostic meters without owning the supplied registry.
 *
 * @author jack
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(MeterBinder.class)
@ConditionalOnBean(ObservationRuntime.class)
@ConditionalOnExpression(
    "${smartscity.observability.enabled:true} and ${smartscity.observability.metrics.enabled:true}")
public class DiagnosticMetricsAutoConfiguration {
  @Bean
  public MeterBinder observationDiagnostics(ObservationRuntime runtime) {
    return registry ->
        FunctionCounter.builder(
                "smartscity.observability.failures", runtime, ObservationRuntime::failures)
            .register(registry);
  }
}
