package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdSnapshot;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Publishes local generator readiness through the matching Spring Boot health API.
 *
 * @author jack
 */
@AutoConfiguration(after = IdAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnSingleCandidate(IdGenerator.class)
@ConditionalOnProperty(
    prefix = "smartscity.id",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class IdHealthAutoConfiguration {
  /**
   * Creates a health indicator from locally observed generator readiness.
   *
   * <p>Polling does not transfer ownership, allocate an ID or perform external I/O. Its approximate
   * snapshot can change immediately after the health check returns.
   *
   * @param generator shared generator supporting concurrent snapshot reads
   * @return an indicator reporting UP when ready and OUT_OF_SERVICE otherwise
   */
  @Bean
  @ConditionalOnMissingBean(name = "smartscityIdHealthIndicator")
  public HealthIndicator smartscityIdHealthIndicator(IdGenerator generator) {
    return () -> {
      IdSnapshot s = generator.snapshot();
      return (s.isReady() ? Health.up() : Health.outOfService())
          .withDetail("state", s.getState())
          .withDetail("buffered", s.getBuffered())
          .withDetail("clockDeviationMillis", s.getClockDeviationMillis())
          .build();
    };
  }
}
