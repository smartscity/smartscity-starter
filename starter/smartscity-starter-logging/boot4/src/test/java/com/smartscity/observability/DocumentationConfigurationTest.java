package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.observability.autoconfigure.ObservabilityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

/**
 * Reads the actual owning YAML example instead of a copied test fixture.
 *
 * @author jack
 */
class DocumentationConfigurationTest {
  @Test
  void bindsActualGuide() throws Exception {
    Path p = java.nio.file.Paths.get("").toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("docs/observability/USER_GUIDE.md"))) {
      p = p.getParent();
    }
    assertNotNull(p);
    String guide =
        new String(
            Files.readAllBytes(p.resolve("docs/observability/USER_GUIDE.md")),
            java.nio.charset.StandardCharsets.UTF_8);
    String fragment =
        guide
            .split("<!-- configuration-example:start -->", 2)[1]
            .split("<!-- configuration-example:end -->", 2)[0];
    String yaml = fragment.split("```yaml", 2)[1].split("```", 2)[0];
    StandardEnvironment env = new StandardEnvironment();
    for (PropertySource<?> source :
        new YamlPropertySourceLoader()
            .load(
                "guide",
                new ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
      env.getPropertySources().addFirst(source);
    }
    ObservabilityProperties value =
        Binder.get(env).bind("smartscity.observability", ObservabilityProperties.class).get();
    assertTrue(value.getCorrelation().isEnabled());
    assertTrue(value.getLogging().isEnabled());
    assertTrue(value.getMasking().isEnabled());
    assertEquals(8192, value.getLogging().getBody().getMaxBytes());
    assertFalse(value.getLogging().getBody().isEnabled());
    assertEquals(List.of("example-sensitive-literal"), value.getMasking().getLiterals());
    assertEquals("datadog", env.getProperty("management.statsd.metrics.export.flavor"));
  }
}
