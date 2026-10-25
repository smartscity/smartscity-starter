package com.smartscity.id.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartscity.id.api.IdException;
import com.smartscity.id.core.GeneratorOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

/**
 * Binds the canonical guide's YAML and checks its documented configuration contract without
 * external service calls.
 *
 * @author jack
 */
class DocumentationConfigurationTest {
  @Test
  void completeGuideConfigurationBindsAndMatchesBothExecutableSamples() throws IOException {
    String yaml = guideYaml();
    for (String sample : new String[] {"id-example", "id-example-boot4"}) {
      assertThat(
              readUtf8(
                  repository()
                      .resolve("samples/id/" + sample + "/src/main/resources/application.yml")))
          .isEqualTo(yaml);
    }
    IdProperties properties = bind(guideSource(), Map.of());
    GeneratorOptions options = properties.options();
    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getGroup()).isEqualTo("orders");
    assertThat(properties.getProfile()).isEqualTo("standard");
    assertThat(options.bits.signature()).isEqualTo("30/18/15");
    assertThat(options.bits.getLayout().getEpoch())
        .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(options.capacity).isEqualTo(16384);
    assertThat(options.lowWaterPercent).isEqualTo(50);
    assertThat(options.waitNanos).isEqualTo(Duration.ofMillis(10).toNanos());
    assertThat(options.clockToleranceMillis).isEqualTo(1000);
    assertThat(properties.getStartupTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.getDatabase().getType()).isEqualTo("mysql");
    assertThat(properties.getDatabase().isUseApplicationDataSource()).isFalse();
    assertThat(properties.getDatabase().getUrl())
        .isEqualTo("jdbc:mysql://127.0.0.1/id_documentation");
    assertThat(properties.getDatabase().getUsername()).isEqualTo("documentation_user");
    assertThat(properties.getDatabase().getPassword()).isEqualTo("documentation_secret");
  }

  @Test
  void omittedPropertiesUseDocumentedDefaults() {
    IdProperties properties = bind(Map.of(), Map.of());
    GeneratorOptions options = properties.options();
    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getGroup()).isEqualTo("default");
    assertThat(properties.getProfile()).isEqualTo("standard");
    assertThat(properties.getEpoch()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(properties.getBits().getTimestamp()).isNull();
    assertThat(properties.getBits().getWorker()).isNull();
    assertThat(properties.getBits().getSequence()).isNull();
    assertThat(options.bits.signature()).isEqualTo("30/18/15");
    assertThat(options.capacity).isEqualTo(16384);
    assertThat(options.lowWaterPercent).isEqualTo(50);
    assertThat(options.waitNanos).isEqualTo(10_000_000L);
    assertThat(options.clockToleranceMillis).isEqualTo(1000);
    assertThat(properties.getJavascriptSequenceBits()).isEqualTo(15);
    assertThat(properties.getStartupTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(properties.getShutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.getWorkerAttempts()).isEqualTo(1024);
    assertThat(properties.getDatabase().getType()).isEqualTo("mysql");
    assertThat(properties.getDatabase().getUrl()).isNull();
    assertThat(properties.getDatabase().isUseApplicationDataSource()).isFalse();
    assertThat(properties.getZookeeper().getRoot()).isEqualTo("/smartscity-id");
    assertThat(properties.getZookeeper().getSessionTimeout()).isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  void higherPrecedencePropertiesOverrideGuideValues() throws IOException {
    IdProperties properties =
        bind(
            guideSource(),
            Map.of(
                "smartscity.id.group", "external-orders",
                "smartscity.id.epoch", "2020-01-01T00:00:00Z",
                "smartscity.id.wait-timeout", "25ms"));
    assertThat(properties.getGroup()).isEqualTo("external-orders");
    assertThat(properties.options().bits.getLayout().getEpoch())
        .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    assertThat(properties.options().waitNanos).isEqualTo(25_000_000L);
  }

  @Test
  void explicitWidthsOverrideProfileButRemovingAllThreeSelectsThePreset() throws IOException {
    Map<String, Object> values = guideSource();
    assertThat(
            bind(values, Map.of("smartscity.id.profile", "high-throughput"))
                .options()
                .bits
                .signature())
        .isEqualTo("30/18/15");
    removeWidths(values);
    assertThat(
            bind(values, Map.of("smartscity.id.profile", "high-throughput"))
                .options()
                .bits
                .signature())
        .isEqualTo("30/16/17");
    assertThat(
            bind(
                    values,
                    Map.of(
                        "smartscity.id.profile",
                        "javascript",
                        "smartscity.id.javascript-sequence-bits",
                        "17"))
                .options()
                .bits
                .signature())
        .isEqualTo("26/10/17");
    assertThatThrownBy(
            () -> bind(guideSource(), Map.of("smartscity.id.profile", "javascript")).options())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("53 bits");
  }

  @Test
  void incompleteWidthsAndInvalidRangesAreRejected() throws IOException {
    Map<String, Object> values = guideSource();
    values.remove("smartscity.id.bits.sequence");
    assertThatThrownBy(() -> bind(values, Map.of()).options())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Set all");
    for (Map<String, Object> invalid :
        List.<Map<String, Object>>of(
            Map.of("smartscity.id.buffer-size", "3"),
            Map.of("smartscity.id.low-water-percent", "100"),
            Map.of("smartscity.id.wait-timeout", "2s"),
            Map.of("smartscity.id.clock-tolerance", "0ms"),
            Map.of("smartscity.id.startup-timeout", "99ms"),
            Map.of("smartscity.id.shutdown-timeout", "31s"),
            Map.of("smartscity.id.group", "contains spaces"))) {
      assertThatThrownBy(() -> bind(guideSource(), invalid).options())
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                bind(guideSource(), Map.of("smartscity.id.epoch", "2026-01-01T00:00:00.001Z"))
                    .options())
        .isInstanceOf(IdException.class);
  }

  @Test
  void invalidScalarTypesFailDuringBinding() {
    for (Map<String, Object> invalid :
        List.<Map<String, Object>>of(
            Map.of("smartscity.id.buffer-size", "many"),
            Map.of("smartscity.id.wait-timeout", "tomorrow"),
            Map.of("smartscity.id.epoch", "not-an-instant"))) {
      assertThatThrownBy(() -> bind(Map.of(), invalid)).isInstanceOf(BindException.class);
    }
  }

  @Test
  void misspelledDocumentedPropertiesCannotSilentlyFallBackToDefaults() throws IOException {
    Map<String, Object> values = guideSource();
    values.put("smartscity.id.buffer-capacity", values.remove("smartscity.id.buffer-size"));
    assertThatThrownBy(() -> bind(values, Map.of())).isInstanceOf(BindException.class);
  }

  @Test
  void missingRequiredDatabaseSelectionFailsWithoutOpeningAConnection() {
    assertThatThrownBy(() -> new IdGeneratorFactory().create(bind(Map.of(), Map.of()), null))
        .isInstanceOfSatisfying(
            IdException.class,
            failure -> assertThat(failure.getReason()).isEqualTo(IdException.Reason.CONFIGURATION));
  }

  private static IdProperties bind(Map<String, Object> documented, Map<String, Object> overrides) {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addLast(new MapPropertySource("guide", documented));
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "exampleEnvironment",
                Map.of(
                    "ID_DATABASE_URL", "jdbc:mysql://127.0.0.1/id_documentation",
                    "ID_DATABASE_USER", "documentation_user",
                    "ID_DATABASE_PASSWORD", "documentation_secret")));
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("externalOverrides", overrides));
    return Binder.get(environment)
        .bindOrCreate(
            "smartscity.id",
            Bindable.of(IdProperties.class),
            new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
  }

  private static Map<String, Object> guideSource() throws IOException {
    PropertySource<?> source =
        new YamlPropertySourceLoader()
            .load("guide", new ByteArrayResource(guideYaml().getBytes(StandardCharsets.UTF_8)))
            .get(0);
    MapPropertySource map = (MapPropertySource) source;
    return new HashMap<>(map.getSource());
  }

  private static void removeWidths(Map<String, Object> values) {
    for (String width : new String[] {"timestamp", "worker", "sequence"}) {
      values.remove("smartscity.id.bits." + width);
    }
  }

  private static String guideYaml() throws IOException {
    String guide = readUtf8(repository().resolve("docs/id/USER_GUIDE.md"));
    Matcher example =
        Pattern.compile(
                "<!-- id-configuration:start -->\\s*```yaml\\n(.*?)```\\s*<!-- id-configuration:end -->",
                Pattern.DOTALL)
            .matcher(guide);
    assertThat(example.find()).as("the guide contains a marked complete YAML example").isTrue();
    String yaml = example.group(1);
    assertThat(example.find())
        .as("the guide has one authoritative configuration example")
        .isFalse();
    return yaml;
  }

  private static String readUtf8(Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  private static Path repository() {
    Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    while (directory != null && !Files.isRegularFile(directory.resolve("docs/id/USER_GUIDE.md"))) {
      directory = directory.getParent();
    }
    if (directory == null) {
      throw new IllegalStateException(
          "Cannot locate the canonical ID guide from the test directory");
    }
    return directory;
  }
}
