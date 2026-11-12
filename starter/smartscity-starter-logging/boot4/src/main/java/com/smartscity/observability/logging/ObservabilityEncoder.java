package com.smartscity.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import com.smartscity.observability.PipeLogEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Marker;

/**
 * Explicit Logback encoder covering ordinary messages, correlated events and bounded exceptions.
 * Safe defaults exist before Spring's context; XML properties configure this appender locally.
 *
 * @author jack
 */
public final class ObservabilityEncoder extends EncoderBase<ILoggingEvent> {
  private boolean maskingEnabled = true;
  private String literals = "";
  private String encodedLiterals;
  private String fields = "";
  private int maxRecordBytes = 16384;
  private String service = "unknown";
  private TextSanitizer sanitizer = TextSanitizer.defaults();
  private PipeLogEncoder encoder = new PipeLogEncoder(16384);

  public void setMaskingEnabled(boolean v) {
    maskingEnabled = v;
  }

  public void setLiterals(String v) {
    literals = v == null ? "" : v;
  }

  /** Accepts the early environment bridge's encoded dictionary without splitting literal commas. */
  public void setEncodedLiterals(String values) {
    encodedLiterals = values;
  }

  /** Adds comma-separated credential field names to the built-in field policy. */
  public void setFields(String values) {
    fields = values == null ? "" : values;
  }

  public void setMaxRecordBytes(int v) {
    maxRecordBytes = v;
  }

  public void setService(String v) {
    service = v;
  }

  @Override
  public void start() {
    java.util.List<String> dictionary = new java.util.ArrayList<>();
    if (encodedLiterals != null && !encodedLiterals.isEmpty()) {
      for (String entry : encodedLiterals.split(",", -1)) {
        dictionary.add(
            new String(java.util.Base64.getDecoder().decode(entry), StandardCharsets.UTF_8));
      }
    } else if (!literals.isEmpty()) {
      dictionary.addAll(Arrays.asList(literals.split(",", -1)));
    }
    sanitizer =
        new TextSanitizer(
            maskingEnabled,
            dictionary,
            fields.isEmpty()
                ? java.util.Collections.emptyList()
                : Arrays.asList(fields.split(",", -1)));
    encoder = new PipeLogEncoder(maxRecordBytes);
    super.start();
  }

  @Override
  public byte[] headerBytes() {
    return null;
  }

  @Override
  public byte[] footerBytes() {
    return null;
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    try {
      String tag = "_undef";
      Map<String, String> values = new LinkedHashMap<>();
      Marker marker = event.getMarker();
      if (marker instanceof EventMarker) {
        tag = ((EventMarker) marker).tag();
        values.putAll(((EventMarker) marker).fields());
      } else {
        Map<String, String> mdc = event.getMDCPropertyMap();
        values.put("traceid", mdc.get("obs.traceid"));
        values.put("spanid", mdc.get("obs.spanid"));
        values.put("cspanid", mdc.get("obs.cspanid"));
        values.put("hintcode", "0");
        values.put("service", service);
        values.put("_msg", sanitizer.sanitize(event.getFormattedMessage()));
        IThrowableProxy t = event.getThrowableProxy();
        if (t != null) {
          StringBuilder stack = new StringBuilder();
          int frames = 0;
          for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            stack
                .append(t.getClassName())
                .append(": ")
                .append(sanitizer.sanitize(t.getMessage()))
                .append('\n');
            for (StackTraceElementProxy frame : t.getStackTraceElementProxyArray()) {
              if (frames++ >= 64 || stack.length() > 32768) {
                break;
              }
              stack.append(frame).append('\n');
            }
          }
          values.put("exception", sanitizer.sanitize(stack.toString()));
        }
      }
      String source = "unknown:0";
      StackTraceElement[] callers = event.getCallerData();
      if (callers != null && callers.length > 0) {
        source = callers[0].getFileName() + ":" + callers[0].getLineNumber();
      }
      return encoder
          .encode(
              event.getLevel().toString(),
              Instant.ofEpochMilli(event.getTimeStamp()),
              source,
              tag,
              values)
          .getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      return "[ERROR][unknown][unknown:0] _undef||_msg=[omitted:encoding-failure]\n"
          .getBytes(StandardCharsets.UTF_8);
    }
  }
}
