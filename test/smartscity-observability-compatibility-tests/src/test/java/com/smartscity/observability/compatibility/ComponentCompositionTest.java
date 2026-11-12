package com.smartscity.observability.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.smartscity.observability.MaskingPolicy;
import com.smartscity.observability.autoconfigure.MetricsAutoConfiguration;
import com.smartscity.observability.autoconfigure.MetricsProperties;
import com.smartscity.observability.autoconfigure.ObservabilityAutoConfiguration;
import com.smartscity.observability.autoconfigure.UdpMetricsAutoConfiguration;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.logging.TextSanitizer;
import com.smartscity.observability.metrics.StatsdUdpSender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Exercises all SDK/logging/metrics assembly combinations and application policy backoff.
 *
 * @author jack
 */
class ComponentCompositionTest {
  @Test
  void allEightComponentAssembliesKeepOwnershipIndependent() {
    for (int bits = 0; bits < 8; bits++) {
      boolean sdk = (bits & 1) != 0;
      boolean logging = (bits & 2) != 0;
      boolean metrics = (bits & 4) != 0;
      List<Class<?>> configs = new ArrayList<>();
      if (logging) {
        configs.add(ObservabilityAutoConfiguration.class);
      }
      if (metrics) {
        configs.add(MetricsAutoConfiguration.class);
        configs.add(UdpMetricsAutoConfiguration.class);
      }
      ApplicationContextRunner runner =
          new ApplicationContextRunner()
              .withPropertyValues("spring.application.name=composition")
              .withConfiguration(AutoConfigurations.of(configs.toArray(new Class<?>[0])));
      MaskingPolicy supplied =
          new MaskingPolicy(true, List.of("account"), List.of(), List.of("private"));
      if (sdk) {
        runner = runner.withBean(MaskingPolicy.class, () -> supplied);
      }
      runner.run(
          c -> {
            assertNull(c.getStartupFailure());
            assertEquals(logging ? 1 : 0, c.getBeansOfType(ObservationRuntime.class).size());
            assertEquals(metrics ? 1 : 0, c.getBeansOfType(MetricsProperties.class).size());
            assertEquals(sdk || logging ? 1 : 0, c.getBeansOfType(MaskingPolicy.class).size());
            assertEquals(0, c.getBeansOfType(StatsdUdpSender.class).size());
            if (sdk) {
              assertSame(supplied, c.getBean(MaskingPolicy.class));
            }
            if (sdk && logging) {
              assertSame(supplied.text(), c.getBean(TextSanitizer.class));
              assertEquals("account=***", c.getBean(TextSanitizer.class).sanitize("account=123"));
            }
          });
    }
  }
}
