package com.smartscity.observability;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes the approved one-line double-pipe contract without permitting field injection.
 *
 * @author jack
 */
public final class PipeLogEncoder {
  private static final java.util.regex.Pattern LEVEL =
      java.util.regex.Pattern.compile("TRACE|DEBUG|INFO|WARN|ERROR|OFF");
  private static final java.util.regex.Pattern TAG =
      java.util.regex.Pattern.compile(
          "_com_request_in|_com_request_out|_com_http_success|_com_http_failure|_undef");
  private static final java.util.regex.Pattern KEY =
      java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private final int maxBytes;

  /**
   * Creates a bounded encoder.
   *
   * @param maxBytes UTF-8 record limit including the newline
   * @throws IllegalArgumentException for an unusable envelope budget
   */
  public PipeLogEncoder(int maxBytes) {
    if (maxBytes < 1024 || maxBytes > 1_048_576) {
      throw new IllegalArgumentException("Invalid log record limit");
    }
    this.maxBytes = maxBytes;
  }

  /**
   * Encodes already sanitized values; payload fields are omitted as whole values when necessary.
   *
   * @param level fixed logger level
   * @param time event time
   * @param source source location, nullable
   * @param tag allowlisted event tag
   * @param fields ordered, sanitized fields
   * @return one physical line; malformed/oversized envelopes become a static safe event
   */
  public String encode(
      String level, Instant time, String source, String tag, Map<String, String> fields) {
    if (!LEVEL.matcher(level).matches() || !TAG.matcher(tag).matches()) {
      throw new IllegalArgumentException("Invalid event envelope");
    }
    String prefix =
        "["
            + level
            + "]["
            + time
            + "]["
            + escape(source == null ? "unknown:0" : source).replace("[", "%5B").replace("]", "%5D")
            + "] "
            + tag;
    StringBuilder mandatory = new StringBuilder(prefix);
    Map<String, String> payload = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : fields.entrySet()) {
      String k = e.getKey();
      if (!KEY.matcher(k).matches()) {
        throw new IllegalArgumentException("Invalid field key");
      }
      if (k.equals("request")
          || k.equals("response")
          || k.equals("_msg")
          || k.equals("exception")) {
        payload.put(k, e.getValue());
      } else {
        mandatory.append("||").append(k).append('=').append(escape(e.getValue()));
      }
    }
    if (bytes(mandatory.toString()) + 1 > maxBytes) {
      return "[ERROR][" + time + "][unknown:0] _undef||_msg=[omitted:envelope-limit]\n";
    }
    for (Map.Entry<String, String> e : payload.entrySet()) {
      String value = "||" + e.getKey() + "=" + escape(e.getValue());
      if (bytes(mandatory.toString()) + bytes(value) + 1 <= maxBytes) {
        mandatory.append(value);
      } else {
        String omitted = "||" + e.getKey() + "=[omitted:record-limit]";
        if (bytes(mandatory.toString()) + bytes(omitted) + 1 <= maxBytes) {
          mandatory.append(omitted);
        }
      }
    }
    return mandatory.append('\n').toString();
  }

  /**
   * Escapes a value exactly once; null and the literal string null remain distinguishable.
   *
   * @param value unencoded value, nullable
   * @return reversible field representation
   */
  public static String escape(String value) {
    if (value == null) {
      return "null";
    }
    if (value.equals("null")) {
      return "%6Eull";
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); ) {
      int c = value.codePointAt(i);
      i += Character.charCount(c);
      if (c == '%' || c == '|' || Character.isISOControl(c) || c == 0x2028 || c == 0x2029) {
        for (byte b : new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8)) {
          out.append('%')
              .append("0123456789ABCDEF".charAt((b >>> 4) & 15))
              .append("0123456789ABCDEF".charAt(b & 15));
        }
      } else {
        out.appendCodePoint(c);
      }
    }
    return out.toString();
  }

  private static int bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }
}
