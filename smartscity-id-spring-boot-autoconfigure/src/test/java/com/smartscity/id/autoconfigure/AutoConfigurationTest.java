package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import com.smartscity.id.core.*;
import com.smartscity.id.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class AutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdAutoConfiguration.class));
    @Test void disabledDoesNotRequireDatabase() {
        runner.withPropertyValues("smartscity.id.enabled=false").run(c -> assertThat(c).doesNotHaveBean(IdGenerator.class));
    }
    @Test void missingDatabaseFailsClearly() {
        runner.run(c -> { assertThat(c).hasFailed(); assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IdException.class); });
    }
    @Test void userBeanBacksOffWithoutDatabaseOrZookeeper() {
        runner.withBean(IdGenerator.class, () -> stub()).withPropertyValues("smartscity.id.profile=javascript")
                .run(c -> { assertThat(c).hasSingleBean(IdGenerator.class); assertThat(c.getBean(IdGenerator.class).getUniqueId()).isPositive(); });
    }
    @Test void propertiesBindConfirmedDefaultsAndJsTradeoff() {
        runner.withBean(IdGenerator.class, () -> stub()).withPropertyValues("smartscity.id.profile=javascript", "smartscity.id.javascript-sequence-bits=17")
                .run(c -> { GeneratorOptions o = c.getBean(IdProperties.class).options();
                    assertThat(o.bits.signature()).isEqualTo("26/10/17"); assertThat(o.waitNanos).isEqualTo(10000000); });
    }
    @Test void disabledOptionalObserversDoNotRequireIdProperties() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(IdAutoConfiguration.class,
                IdHealthAutoConfiguration.class, IdMetricsAutoConfiguration.class))
                .withBean(IdGenerator.class, () -> stub()).withPropertyValues("smartscity.id.enabled=false")
                .run(c -> { assertThat(c).hasNotFailed(); assertThat(c).doesNotHaveBean("smartscityIdMetrics"); });
    }
    @Test void healthAndMetricsReflectGeneratedIds() {
        new ApplicationContextRunner().withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader("org.springframework.boot.health"))
                .withConfiguration(AutoConfigurations.of(IdAutoConfiguration.class,
                IdHealthAutoConfiguration.class, IdMetricsAutoConfiguration.class))
                .withBean(IdGenerator.class, () -> stub()).run(c -> {
                    assertThat(c).hasNotFailed();
                    IdGenerator generator = c.getBean(IdGenerator.class); generator.getUniqueId();
                    org.springframework.boot.actuate.health.HealthIndicator health = c.getBean("smartscityIdHealthIndicator", org.springframework.boot.actuate.health.HealthIndicator.class);
                    assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
                    io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
                    try {
                        c.getBean("smartscityIdMetrics", io.micrometer.core.instrument.binder.MeterBinder.class).bindTo(meters);
                        assertThat(meters.get("smartscity.id.generated").functionCounter().count()).isEqualTo(1);
                        assertThat(meters.get("smartscity.id.latency.upper.nanoseconds").tag("quantile", "0.99").gauge().value()).isPositive();
                        generator.close(); assertThat(health.health().getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
                    } finally { meters.close(); }
                });
    }
    static IdGenerator stub() {
        return new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(8).build(), new WorkerLease() {
            public long rawId() { return 1; } public long workerId() { return 1; }
            public State state() { return State.ACTIVE; } public void close() { }
        }, TimeSource.SYSTEM);
    }
}
