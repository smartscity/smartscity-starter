package com.smartscity.id.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.core.IdLayout;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies property binding, conditional backoff and ownership of application resources.
 *
 * @author jack
 */
class AutoConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(IdAutoConfiguration.class));

  @Test
  void disabledDoesNotRequireDatabase() {
    runner
        .withPropertyValues("smartscity.id.enabled=false")
        .run(c -> assertThat(c).doesNotHaveBean(IdGenerator.class));
  }

  @Test
  void missingDatabaseFailsClearly() {
    runner.run(
        c -> {
          assertThat(c).hasFailed();
          assertThat(c.getStartupFailure()).hasRootCauseInstanceOf(IdException.class);
        });
  }

  @Test
  void userBeanBacksOffWithoutDatabaseOrZookeeper() {
    runner
        .withBean(IdGenerator.class, () -> stub())
        .withPropertyValues("smartscity.id.profile=javascript")
        .run(
            c -> {
              assertThat(c).hasSingleBean(IdGenerator.class);
              assertThat(c.getBean(IdGenerator.class).getUniqueId()).isPositive();
            });
  }

  @Test
  void propertiesBindConfirmedDefaultsAndJsTradeoff() {
    runner
        .withBean(IdGenerator.class, () -> stub())
        .withPropertyValues(
            "smartscity.id.profile=javascript", "smartscity.id.javascript-sequence-bits=17")
        .run(
            c -> {
              GeneratorOptions o = c.getBean(IdProperties.class).options();
              assertThat(o.bits.signature()).isEqualTo("26/10/17");
              assertThat(o.waitNanos).isEqualTo(10000000);
            });
  }

  @Test
  void disabledOptionalObserversDoNotRequireIdProperties() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                IdAutoConfiguration.class,
                IdHealthAutoConfiguration.class,
                IdMetricsAutoConfiguration.class))
        .withBean(IdGenerator.class, () -> stub())
        .withPropertyValues("smartscity.id.enabled=false")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).doesNotHaveBean("smartscityIdMetrics");
            });
  }

  @Test
  void healthAndMetricsReflectGeneratedIds() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                IdAutoConfiguration.class,
                IdHealthAutoConfiguration.class,
                IdMetricsAutoConfiguration.class))
        .withBean(IdGenerator.class, () -> stub())
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              IdGenerator generator = c.getBean(IdGenerator.class);
              generator.getUniqueId();
              Object health = c.getBean("smartscityIdHealthIndicator");
              assertThat(healthStatus(health)).isEqualTo("UP");
              io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                  new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
              try {
                c.getBean(
                        "smartscityIdMetrics",
                        io.micrometer.core.instrument.binder.MeterBinder.class)
                    .bindTo(meters);
                assertThat(meters.get("smartscity.id.generated").functionCounter().count())
                    .isEqualTo(1);
                assertThat(
                        meters
                            .get("smartscity.id.latency.upper.nanoseconds")
                            .tag("quantile", "0.99")
                            .gauge()
                            .value())
                    .isPositive();
                generator.close();
                assertThat(healthStatus(health)).isEqualTo("OUT_OF_SERVICE");
              } finally {
                meters.close();
              }
            });
  }

  @Test
  void customLayoutBindsAndOptionsAreAnImmutableSnapshot() {
    runner
        .withBean(IdGenerator.class, AutoConfigurationTest::stub)
        .withPropertyValues(
            "smartscity.id.epoch=2020-01-01T00:00:00Z",
            "smartscity.id.bits.timestamp=31",
            "smartscity.id.bits.worker=17",
            "smartscity.id.bits.sequence=15")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              IdProperties p = c.getBean(IdProperties.class);
              GeneratorOptions options = p.options();
              assertThat(options.bits.signature()).isEqualTo("31/17/15");
              assertThat(options.bits.getLayout().getEpoch())
                  .isEqualTo(java.time.Instant.parse("2020-01-01T00:00:00Z"));
              p.setEpoch(java.time.Instant.EPOCH);
              p.getBits().setTimestamp(30);
              assertThat(options.bits.signature()).isEqualTo("31/17/15");
              assertThat(options.bits.getLayout().getEpoch())
                  .isEqualTo(java.time.Instant.parse("2020-01-01T00:00:00Z"));
            });
    runner
        .withBean(IdGenerator.class, AutoConfigurationTest::stub)
        .run(
            c -> {
              GeneratorOptions options = c.getBean(IdProperties.class).options();
              assertThat(options.bits.signature()).isEqualTo("30/18/15");
              assertThat(options.bits.getLayout().getEpoch()).isEqualTo(IdLayout.DEFAULT_EPOCH);
              assertThat(options.capacity).isEqualTo(16384);
            });
  }

  @Test
  void invalidLayoutFailsBeforeDatabaseAllocation() {
    for (String[] properties :
        new String[][] {
          {"smartscity.id.bits.timestamp=30"},
          {
            "smartscity.id.bits.timestamp=31",
            "smartscity.id.bits.worker=18",
            "smartscity.id.bits.sequence=15"
          },
          {"smartscity.id.epoch=2020-01-01T00:00:00.001Z"},
          {
            "smartscity.id.profile=javascript",
            "smartscity.id.bits.timestamp=30",
            "smartscity.id.bits.worker=18",
            "smartscity.id.bits.sequence=15"
          }
        }) {
      runner
          .withPropertyValues(properties)
          .run(
              c -> {
                assertThat(c).hasFailed();
                assertThat(c.getStartupFailure())
                    .hasStackTraceContaining("Invalid ID configuration");
              });
    }
  }

  @Test
  void applicationDataSourceIsObtainedOnlyOnExplicitOptIn() throws Exception {
    java.util.concurrent.atomic.AtomicInteger borrowed =
        new java.util.concurrent.atomic.AtomicInteger();
    javax.sql.DataSource fixture = new LifecycleReviewTest().database();
    // H2 transaction mechanics are adequate here, but its MySQL time function uses 32-bit
    // arithmetic.
    javax.sql.DataSource dataSource =
        LifecycleReviewTest.proxy(
            javax.sql.DataSource.class,
            (m, a) -> {
              Object result = LifecycleReviewTest.invoke(fixture, m, a);
              if (!m.getName().equals("getConnection")) {
                return result;
              }
              borrowed.incrementAndGet();
              return LifecycleReviewTest.proxy(
                  java.sql.Connection.class,
                  (cm, ca) -> {
                    if (cm.getName().equals("close")) {
                      borrowed.decrementAndGet();
                    }
                    if (cm.getName().equals("prepareStatement")
                        && ca[0].toString().contains("UNIX_TIMESTAMP")) {
                      ca[0] = "SELECT " + System.currentTimeMillis();
                    }
                    return LifecycleReviewTest.invoke(result, cm, ca);
                  });
            });
    java.util.concurrent.atomic.AtomicInteger created =
        new java.util.concurrent.atomic.AtomicInteger();
    ApplicationContextRunner configured =
        runner.withBean(
            "applicationDataSource",
            javax.sql.DataSource.class,
            () -> {
              created.incrementAndGet();
              return dataSource;
            },
            definition -> definition.setLazyInit(true));
    configured.run(
        c -> {
          assertThat(c).hasFailed();
          assertThat(created.get()).isZero();
        });
    configured
        .withPropertyValues(
            "smartscity.id.database.use-application-data-source=true",
            "smartscity.id.buffer-size=8")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(created.get()).isEqualTo(1);
              assertThat(borrowed.get()).isZero();
              assertThat(c.getBean(IdGenerator.class).getUniqueId()).isPositive();
            });
    assertThat(borrowed.get()).isZero();
    // The application still owns and can borrow from its DataSource after context destruction.
    try (java.sql.Connection connection = dataSource.getConnection()) {
      assertThat(connection.isClosed()).isFalse();
    }
  }

  private static String healthStatus(Object indicator) throws Exception {
    String type =
        org.springframework.util.ClassUtils.isPresent(
                "org.springframework.boot.health.contributor.HealthIndicator",
                indicator.getClass().getClassLoader())
            ? "org.springframework.boot.health.contributor.HealthIndicator"
            : "org.springframework.boot.actuate.health.HealthIndicator";
    Object health = Class.forName(type).getMethod("health").invoke(indicator);
    return health.getClass().getMethod("getStatus").invoke(health).toString();
  }

  static IdGenerator stub() {
    return new CachedUniqueIdGenerator(
        GeneratorOptions.builder().capacity(8).build(),
        new WorkerLease() {
          public long rawId() {
            return 1;
          }

          public long workerId() {
            return 1;
          }

          public State state() {
            return State.ACTIVE;
          }

          public void close() {}
        },
        TimeSource.SYSTEM);
  }
}
