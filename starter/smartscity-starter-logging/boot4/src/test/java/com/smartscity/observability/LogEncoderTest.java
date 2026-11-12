package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.smartscity.observability.logging.ObservabilityEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies masking and escaping for ordinary application logs and throwable chains.
 *
 * @author jack
 */
class LogEncoderTest {
  @Test
  void formattedArgumentsAndThrowableCannotBypassMasking() {
    LoggerContext context = new LoggerContext();
    Logger logger = context.getLogger("application");
    ObservabilityEncoder encoder = new ObservabilityEncoder();
    encoder.setContext(context);
    encoder.setLiterals("private-value");
    encoder.start();
    try {
      LoggingEvent event =
          new LoggingEvent(
              getClass().getName(),
              logger,
              Level.INFO,
              "token={} | note=ok",
              new IllegalStateException("password=secret; note=private-value"),
              new Object[] {"private-value"});
      event.setTimeStamp(0);
      event.setMDCPropertyMap(java.util.Collections.emptyMap());
      String output = new String(encoder.encode(event), StandardCharsets.UTF_8);
      assertTrue(output.startsWith("[INFO][1970-01-01T00:00:00Z]"));
      assertFalse(output.contains("private-value"));
      assertFalse(output.contains("password=secret"));
      assertTrue(output.contains("%7C"));
      assertEquals(1, output.chars().filter(c -> c == '\n').count());
    } finally {
      encoder.stop();
      context.stop();
    }
  }
}
