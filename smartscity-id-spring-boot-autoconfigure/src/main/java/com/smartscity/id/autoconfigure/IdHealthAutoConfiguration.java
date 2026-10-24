package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

@AutoConfiguration(after = IdAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnMissingClass("org.springframework.boot.health.contributor.HealthIndicator")
@ConditionalOnSingleCandidate(IdGenerator.class)
@ConditionalOnProperty(prefix = "smartscity.id", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdHealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "smartscityIdHealthIndicator")
    public HealthIndicator smartscityIdHealthIndicator(IdGenerator generator) {
        return () -> {
            IdSnapshot s = generator.snapshot();
            return (s.isReady() ? Health.up() : Health.outOfService()).withDetail("state", s.getState())
                    .withDetail("buffered", s.getBuffered()).withDetail("clockDeviationMillis", s.getClockDeviationMillis()).build();
        };
    }
}
