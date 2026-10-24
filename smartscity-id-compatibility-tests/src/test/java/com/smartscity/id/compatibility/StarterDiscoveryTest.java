package com.smartscity.id.compatibility;

import com.smartscity.id.api.*;
import com.smartscity.id.core.*;
import com.smartscity.id.spi.*;
import com.smartscity.id.autoconfigure.IdAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.assertThat;

class StarterDiscoveryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean(destroyMethod = "close") IdGenerator customGenerator() {
            return new CachedUniqueIdGenerator(GeneratorOptions.builder().capacity(8).build(), new WorkerLease() {
                public long rawId() { return 3; }
                public long workerId() { return 3; }
                public State state() { return State.ACTIVE; }
                public void close() { }
            }, TimeSource.SYSTEM);
        }
    }
    @Test void publishedImportsAreDiscoveredAndUserBeanWins() {
        new ApplicationContextRunner().withUserConfiguration(Application.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IdGenerator.class).hasSingleBean(IdAutoConfiguration.class);
            assertThat(context).hasBean("smartscityIdHealthIndicator").hasBean("smartscityIdMetrics");
            long id = context.getBean(IdGenerator.class).getUniqueId();
            assertThat(id).isPositive(); assertThat(context.getBean(IdGenerator.class).parse(id).getWorkerId()).isEqualTo(3);
            Object indicator = context.getBean("smartscityIdHealthIndicator");
            String healthType = org.springframework.util.ClassUtils.isPresent("org.springframework.boot.health.contributor.HealthIndicator", context.getClassLoader())
                    ? "org.springframework.boot.health.contributor.HealthIndicator" : "org.springframework.boot.actuate.health.HealthIndicator";
            java.lang.reflect.Method health = Class.forName(healthType).getMethod("health");
            Object up = health.invoke(indicator);
            assertThat(up.getClass().getMethod("getStatus").invoke(up).toString()).isEqualTo("UP");
            context.getBean(IdGenerator.class).close();
            Object down = health.invoke(indicator);
            assertThat(down.getClass().getMethod("getStatus").invoke(down).toString()).isEqualTo("OUT_OF_SERVICE");
        });
    }
    @Test void disableRemovesAutoConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(Application.class)
                .withPropertyValues("smartscity.id.enabled=false").run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(IdAutoConfiguration.class);
                    assertThat(context.getBean(IdGenerator.class).getUniqueId()).isPositive();
                });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DatabaseApplication { }
    @Test
    @EnabledIfSystemProperty(named = "id.test.mysql.url", matches = ".+")
    void realStandardStarterWorksWithoutOptionalZookeeperClasses() {
        new ApplicationContextRunner().withUserConfiguration(DatabaseApplication.class)
                .withPropertyValues("smartscity.id.database.url=" + System.getProperty("id.test.mysql.url"),
                        "smartscity.id.database.username=root", "smartscity.id.group=starter_" + System.nanoTime(),
                        "smartscity.id.buffer-size=64")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(IdGenerator.class);
                    assertThat(context.getBean(IdGenerator.class).getUniqueId()).isPositive();
                });
    }

}
