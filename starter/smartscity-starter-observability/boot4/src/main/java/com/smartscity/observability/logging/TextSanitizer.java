package com.smartscity.observability.logging;

import com.smartscity.observability.LiteralMasker;
import com.smartscity.observability.RedactionRanges;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded literal and key/value redaction for formatted messages and exception text. Arbitrary
 * secrets without an explicit matching rule cannot be inferred.
 *
 * @author jack
 */
public final class TextSanitizer {
  private final LiteralMasker matcher;
  private final boolean enabled;
  private final Set<String> fields = new LinkedHashSet<>();

  public TextSanitizer(boolean enabled, Collection<String> literals) {
    this(enabled, literals, Collections.emptyList());
  }

  public TextSanitizer(boolean enabled, Collection<String> literals, Collection<String> keys) {
    this.enabled = enabled;
    matcher = new LiteralMasker(literals, 65536);
    fields.addAll(
        Arrays.asList(
            "password",
            "passwd",
            "secret",
            "token",
            "access_token",
            "refresh_token",
            "authorization",
            "cookie",
            "set-cookie",
            "credential"));
    if (keys.size() > 128) {
      throw new IllegalArgumentException("Too many mask fields");
    }
    for (String k : keys) {
      if (k == null || !k.matches("[A-Za-z0-9_.-]{1,128}")) {
        throw new IllegalArgumentException("Invalid mask field");
      }
      fields.add(k.toLowerCase(Locale.ROOT));
    }
  }

  public static TextSanitizer defaults() {
    return new TextSanitizer(true, Collections.emptyList());
  }

  public String sanitize(String text) {
    if (text == null) {
      return null;
    }
    if (text.length() > 65536) {
      return "[omitted:limit]";
    }
    if (!enabled) {
      return text;
    }
    RedactionRanges ranges = new RedactionRanges(text);
    int i = 0;
    while (i < text.length()) {
      int start = i;
      if (Character.isLetter(text.charAt(i))) {
        while (i < text.length()
            && (Character.isLetterOrDigit(text.charAt(i)) || "_.-".indexOf(text.charAt(i)) >= 0)) {
          i++;
        }
        String key = text.substring(start, i).toLowerCase(Locale.ROOT);
        if (fields.contains(key)) {
          int separator = i;
          if (separator < text.length()
              && (text.charAt(separator) == '"' || text.charAt(separator) == '\'')) {
            separator++;
          }
          while (separator < text.length() && Character.isWhitespace(text.charAt(separator))) {
            separator++;
          }
          if (separator < text.length()
              && (text.charAt(separator) == ':' || text.charAt(separator) == '=')) {
            int value = separator + 1;
            while (value < text.length() && Character.isWhitespace(text.charAt(value))) {
              value++;
            }
            int end = value;
            if (end < text.length() && (text.charAt(end) == '"' || text.charAt(end) == '\'')) {
              char quote = text.charAt(end++);
              int contents = end;
              boolean escaped = false;
              boolean terminated = false;
              while (end < text.length()) {
                char c = text.charAt(end++);
                if (escaped) {
                  escaped = false;
                } else if (c == '\\') {
                  escaped = true;
                } else if (c == quote) {
                  terminated = true;
                  break;
                }
              }
              ranges.cover(contents, terminated ? end - 1 : end);
            } else {
              while (end < text.length() && ",;&|}\r\n".indexOf(text.charAt(end)) < 0) {
                end++;
              }
              ranges.cover(value, end);
            }
            i = end;
          }
        }
      } else {
        i++;
      }
    }
    matcher.cover(ranges);
    return ranges.render();
  }
}
