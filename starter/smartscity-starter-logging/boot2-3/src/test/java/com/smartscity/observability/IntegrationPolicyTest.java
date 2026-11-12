package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.observability.autoconfigure.ObservabilityAutoConfiguration;
import com.smartscity.observability.autoconfigure.ObservabilityProperties;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.logging.JsonBodySanitizer;
import com.smartscity.observability.logging.TextSanitizer;
import com.smartscity.observability.tracing.CorrelationScope;
import com.smartscity.observability.tracing.CorrelationTaskDecorator;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Exercises optional dependency absence, all switch combinations and safe data handling.
 *
 * @author jack
 */
class IntegrationPolicyTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
          .withPropertyValues("spring.application.name=policy-test");

  @Test
  void allSixteenCapabilityCombinations() {
    for (int mask = 0; mask < 16; mask++) {
      final int m = mask;
      runner
          .withPropertyValues(
              "smartscity.observability.correlation.enabled=" + ((m & 1) != 0),
              "smartscity.observability.logging.enabled=" + ((m & 2) != 0),
              "smartscity.observability.masking.enabled=" + ((m & 4) != 0),
              "smartscity.observability.metrics.enabled=" + ((m & 8) != 0))
          .run(
              c -> {
                assertNull(c.getStartupFailure());
                assertEquals((m & 3) != 0, c.getBeansOfType(ObservationRuntime.class).size() == 1);
                assertEquals(
                    (m & 1) != 0, c.getBeansOfType(CorrelationTaskDecorator.class).size() == 1);
                assertFalse(
                    org.springframework.util.ClassUtils.isPresent(
                        "com.smartscity.observability.metrics.StatsdUdpSender",
                        c.getClassLoader()));
              });
    }
  }

  @Test
  void disabledAndMissingOptionalLibraries() {
    runner
        .withPropertyValues("smartscity.observability.enabled=false")
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              assertTrue(c.getBeansOfType(ObservationRuntime.class).isEmpty());
            });
    runner
        .withClassLoader(new FilteredClassLoader("com.fasterxml.jackson", "io.micrometer.statsd"))
        .run(
            c -> {
              assertNull(c.getStartupFailure());
              assertNotNull(c.getBean(ObservationRuntime.class));
            });
  }

  @Test
  void invalidBodyOptInFailsClearly() {
    runner
        .withPropertyValues("smartscity.observability.logging.body.enabled=true")
        .run(c -> assertNotNull(c.getStartupFailure()));
    for (String invalid :
        Arrays.asList(
            "smartscity.observability.logging.body.enabled=true",
            "smartscity.observability.logging.body.max-bytes=1048577")) {
      runner
          .withBean(
              MaskingPolicy.class,
              () ->
                  new MaskingPolicy(
                      true,
                      java.util.Collections.emptyList(),
                      java.util.Collections.emptyList(),
                      java.util.Collections.emptyList()))
          .withPropertyValues(invalid)
          .run(c -> assertNotNull(c.getStartupFailure()));
    }
  }

  @Test
  void nestedJsonAndMalformedPayloadNeverLeak() {
    ObservabilityProperties p = new ObservabilityProperties();
    p.getMasking().getPaths().add("/items/0/value");
    TextSanitizer text = new TextSanitizer(true, Arrays.asList("literal-secret"));
    JsonBodySanitizer json = new JsonBodySanitizer(p.getMasking(), text);
    String safe =
        json.sanitize(
            "{\"password\":\"abc\",\"items\":[{\"value\":\"hidden\"}],\"note\":\"literal-secret\"}"
                .getBytes(StandardCharsets.UTF_8),
            "application/json");
    assertFalse(safe.contains("abc"));
    assertFalse(safe.contains("hidden"));
    assertFalse(safe.contains("literal-secret"));
    assertTrue(
        json.sanitize("{\"password\":\"raw".getBytes(StandardCharsets.UTF_8), "application/json")
            .startsWith("[omitted:"));
    assertFalse(text.sanitize("password=abcdef; token=xyz; ok=1").contains("abcdef"));
    assertFalse(text.sanitize("{\"password\":\"abc\\\"def\"}").contains("abc"));
  }

  @Test
  void literalMaskingSeesDecodedJsonStrings() throws Exception {
    ObservabilityProperties p = new ObservabilityProperties();
    TextSanitizer text = new TextSanitizer(true, Arrays.asList("a\"b\\c"));
    JsonBodySanitizer sanitizer = new JsonBodySanitizer(p.getMasking(), text);
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    byte[] input =
        mapper.writeValueAsBytes(
            java.util.Collections.singletonMap("items", Arrays.asList("a\"b\\c")));
    String safe = sanitizer.sanitize(input, "application/json");
    assertEquals("***", mapper.readTree(safe).get("items").get(0).textValue());
  }

  @Test
  void credentialAndLiteralRulesMatchTheSameOriginalText() {
    TextSanitizer mixed = new TextSanitizer(true, Arrays.asList("abc,def", "token"));
    assertEquals("password=***", mixed.sanitize("password=abc,def"));
    assertEquals("***=***", mixed.sanitize("token=secret"));
    assertEquals(
        "note=***", new TextSanitizer(true, Arrays.asList("a|b;c")).sanitize("note=a|b;c"));
    assertEquals("password=\"***\"", mixed.sanitize("password=\"abc,def\""));
  }

  @Test
  void invalidMaskPointersFailAndRootPointerCoversTheWholeDocument() {
    ObservabilityProperties p = new ObservabilityProperties();
    p.getMasking().getPaths().add("account/~2value");
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new JsonBodySanitizer(p.getMasking(), TextSanitizer.defaults()));
    p.getMasking().getPaths().clear();
    p.getMasking().getPaths().add("");
    assertEquals(
        "\"***\"",
        new JsonBodySanitizer(p.getMasking(), TextSanitizer.defaults())
            .sanitize(
                "{\"anything\":\"secret\"}".getBytes(StandardCharsets.UTF_8), "application/json"));
  }

  @Test
  void scopesAndDecoratorsRestoreNestedThreadState() throws Exception {
    CorrelationContext outer = CorrelationContext.root("outer");
    CorrelationContext inner = outer.child("outer", "inner");
    try (CorrelationScope one = CorrelationScope.open(outer)) {
      Runnable captured =
          new CorrelationTaskDecorator()
              .decorate(
                  () -> assertEquals(outer.currentId(), CorrelationScope.current().currentId()));
      try (CorrelationScope two = CorrelationScope.open(inner)) {
        captured.run();
        assertEquals(inner, CorrelationScope.current());
      }
      assertEquals(outer, CorrelationScope.current());
    }
    assertNull(CorrelationScope.current());
  }
}
