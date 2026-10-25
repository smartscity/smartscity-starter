package com.smartscity.id.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.autoconfigure.IdAutoConfiguration;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies installed starter discovery and runtime behavior under independent Boot BOMs.
 *
 * @author jack
 */
class StarterDiscoveryTest {
  @org.junit.jupiter.api.BeforeAll
  static void prepareExplicitTestDatabase() throws Exception {
    String url = System.getProperty("id.test.mysql.url");
    if (url == null || url.isEmpty()) {
      return;
    }
    if (!url.contains("smartscity_id_test")) {
      throw new IllegalArgumentException("Use an isolated test database");
    }
    String ddl =
        new String(
            StarterDiscoveryTest.class.getResourceAsStream("/db/mysql.sql").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, "root", "");
        java.sql.Statement statement = connection.createStatement()) {
      for (String sql : ddl.split(";")) {
        if (!sql.trim().isEmpty()) {
          statement.execute(sql.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS "));
        }
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class Application {
    @Bean(destroyMethod = "close")
    IdGenerator customGenerator() {
      return new CachedUniqueIdGenerator(
          GeneratorOptions.builder().capacity(8).build(),
          new WorkerLease() {
            public long rawId() {
              return 3;
            }

            public long workerId() {
              return 3;
            }

            public State state() {
              return State.ACTIVE;
            }

            public void close() {}
          },
          TimeSource.SYSTEM);
    }
  }

  @Test
  void publishedImportsAreDiscoveredAndUserBeanWins() {
    new ApplicationContextRunner()
        .withUserConfiguration(Application.class)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(IdGenerator.class)
                  .hasSingleBean(IdAutoConfiguration.class);
              assertThat(context)
                  .hasBean("smartscityIdHealthIndicator")
                  .hasBean("smartscityIdMetrics");
              long id = context.getBean(IdGenerator.class).getUniqueId();
              assertThat(id).isPositive();
              assertThat(context.getBean(IdGenerator.class).parse(id).getWorkerId()).isEqualTo(3);
              Object indicator = context.getBean("smartscityIdHealthIndicator");
              String healthType =
                  org.springframework.util.ClassUtils.isPresent(
                          "org.springframework.boot.health.contributor.HealthIndicator",
                          context.getClassLoader())
                      ? "org.springframework.boot.health.contributor.HealthIndicator"
                      : "org.springframework.boot.actuate.health.HealthIndicator";
              java.lang.reflect.Method health = Class.forName(healthType).getMethod("health");
              Object up = health.invoke(indicator);
              assertThat(up.getClass().getMethod("getStatus").invoke(up).toString())
                  .isEqualTo("UP");
              context.getBean(IdGenerator.class).close();
              Object down = health.invoke(indicator);
              assertThat(down.getClass().getMethod("getStatus").invoke(down).toString())
                  .isEqualTo("OUT_OF_SERVICE");
            });
  }

  @Test
  void disableRemovesAutoConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(Application.class)
        .withPropertyValues("smartscity.id.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(IdAutoConfiguration.class);
              assertThat(context.getBean(IdGenerator.class).getUniqueId()).isPositive();
            });
  }

  @Test
  void propertiesBindThroughPublishedStarter() {
    new ApplicationContextRunner()
        .withUserConfiguration(Application.class)
        .withPropertyValues(
            "smartscity.id.profile=javascript",
            "smartscity.id.javascript-sequence-bits=17",
            "smartscity.id.buffer-size=128",
            "smartscity.id.wait-timeout=25ms",
            "smartscity.id.epoch=2020-01-01T00:00:00Z")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              com.smartscity.id.autoconfigure.IdProperties properties =
                  context.getBean(com.smartscity.id.autoconfigure.IdProperties.class);
              assertThat(properties.options().bits.signature()).isEqualTo("26/10/17");
              assertThat(properties.options().bits.getLayout().getEpoch())
                  .isEqualTo(java.time.Instant.parse("2020-01-01T00:00:00Z"));
              assertThat(properties.options().capacity).isEqualTo(128);
              assertThat(properties.options().waitNanos).isEqualTo(25000000);
            });
  }

  @Test
  void optionalObserversBackOffWhenTheirLibrariesAreAbsent() {
    new ApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                com.smartscity.id.autoconfigure.IdAutoConfiguration.class,
                com.smartscity.id.autoconfigure.IdHealthAutoConfiguration.class,
                com.smartscity.id.autoconfigure.IdMetricsAutoConfiguration.class))
        .withBean(IdGenerator.class, () -> new Application().customGenerator())
        .withClassLoader(
            new org.springframework.boot.test.context.FilteredClassLoader(
                "org.springframework.boot.actuate",
                "org.springframework.boot.health",
                "io.micrometer"))
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(IdGenerator.class);
              assertThat(context)
                  .doesNotHaveBean("smartscityIdHealthIndicator")
                  .doesNotHaveBean("smartscityIdMetrics");
            });
  }

  @Test
  void springApplicationStartsAndCloses() {
    org.springframework.boot.SpringApplication app =
        new org.springframework.boot.SpringApplication(Application.class);
    app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    try (org.springframework.context.ConfigurableApplicationContext context =
        app.run("--spring.main.banner-mode=off")) {
      assertThat(context.isActive()).isTrue();
      assertThat(context.getBean(IdGenerator.class).getUniqueId()).isPositive();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class DatabaseApplication {}

  @Test
  @EnabledIfSystemProperty(named = "id.test.mysql.url", matches = ".+")
  void realStandardStarterWorksWithoutOptionalZookeeperClasses() {
    new ApplicationContextRunner()
        .withUserConfiguration(DatabaseApplication.class)
        .withPropertyValues(
            "smartscity.id.database.url=" + System.getProperty("id.test.mysql.url"),
            "smartscity.id.database.username=root",
            "smartscity.id.group=starter_" + System.nanoTime(),
            "smartscity.id.buffer-size=64")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(IdGenerator.class);
              assertThat(context.getBean(IdGenerator.class).getUniqueId()).isPositive();
            });
  }

  @Test
  void customWidthsBindThroughPublishedStarter() {
    new ApplicationContextRunner()
        .withUserConfiguration(Application.class)
        .withPropertyValues(
            "smartscity.id.bits.timestamp=31",
            "smartscity.id.bits.worker=17",
            "smartscity.id.bits.sequence=15",
            "smartscity.id.epoch=2020-01-01T00:00:00Z")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              com.smartscity.id.autoconfigure.IdProperties properties =
                  context.getBean(com.smartscity.id.autoconfigure.IdProperties.class);
              assertThat(properties.options().bits.signature()).isEqualTo("31/17/15");
              assertThat(properties.options().bits.getLayout().getEpoch())
                  .isEqualTo(java.time.Instant.parse("2020-01-01T00:00:00Z"));
            });
  }

  @Test
  @EnabledIfSystemProperty(named = "id.test.mysql.url", matches = ".+")
  void customFormatGeneratesAndRejectsIncompatibleRestart() {
    String group = "custom_" + System.nanoTime();
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withUserConfiguration(DatabaseApplication.class)
            .withPropertyValues(
                "smartscity.id.database.url=" + System.getProperty("id.test.mysql.url"),
                "smartscity.id.database.username=root",
                "smartscity.id.group=" + group,
                "smartscity.id.epoch=2020-01-01T00:00:00Z",
                "smartscity.id.bits.timestamp=31",
                "smartscity.id.bits.worker=17",
                "smartscity.id.bits.sequence=15");
    java.util.Set<Long> seen = new java.util.HashSet<>();
    for (int restart = 0; restart < 2; restart++) {
      runner.run(
          context -> {
            assertThat(context).hasNotFailed();
            IdGenerator generator = context.getBean(IdGenerator.class);
            long id = generator.getUniqueId();
            assertThat(seen.add(id)).isTrue();
            assertThat(generator.parse(id).getTimestamp())
                .isBetween(
                    java.time.Instant.now().minusSeconds(30),
                    java.time.Instant.now().plusSeconds(1));
            assertThat(generator.snapshot().isReady()).isTrue();
          });
    }
    runner
        .withPropertyValues("smartscity.id.epoch=2021-01-01T00:00:00Z")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("smartscity.id.bits.timestamp=30")
        .run(context -> assertThat(context).hasFailed());
  }
}
