package com.smartscity.observability;

import com.smartscity.observability.logging.TextSanitizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, framework-free field/path decisions and compiled text masking for shared use.
 *
 * <p>Callers adapt their own data model and pass decoded text to the sanitizer. This policy does
 * not parse JSON, mutate business data or own external resources.
 *
 * @author jack
 */
public final class MaskingPolicy {
  private final boolean enabled;
  private final Set<String> fields;
  private final Set<String> paths;
  private final TextSanitizer text;

  /**
   * Compiles a bounded policy, copying all supplied collections before publication.
   *
   * @param enabled whether redaction is enabled; text bounds still apply when disabled
   * @param fields non-null additional case-insensitive field names
   * @param paths non-null exact JSON Pointers, including the empty root pointer
   * @param literals non-null literal dictionary
   * @throws IllegalArgumentException if any policy bound or syntax is invalid
   * @throws NullPointerException if a collection is null
   */
  public MaskingPolicy(
      boolean enabled,
      Collection<String> fields,
      Collection<String> paths,
      Collection<String> literals) {
    this.enabled = enabled;
    Objects.requireNonNull(fields, "fields");
    Objects.requireNonNull(paths, "paths");
    text = new TextSanitizer(enabled, literals, fields);
    this.fields = new HashSet<>();
    for (String field : fields) {
      this.fields.add(field.toLowerCase(Locale.ROOT));
    }
    if (paths.size() > 128) {
      throw new IllegalArgumentException("Too many mask paths");
    }
    this.paths = new HashSet<>();
    for (String path : paths) {
      if (path == null || path.length() > 4096 || !validPointer(path)) {
        throw new IllegalArgumentException("Invalid mask JSON Pointer");
      }
      this.paths.add(path);
    }
  }

  /**
   * Returns whether a decoded field name is explicitly masked by this policy.
   *
   * @param name decoded field name, or null
   * @return true when enabled and the name matches case-insensitively
   */
  public boolean masksField(String name) {
    return enabled && name != null && fields.contains(name.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns whether an exact JSON Pointer is masked by this policy.
   *
   * @param pointer exact pointer, or null
   * @return true when enabled and the pointer matches
   */
  public boolean masksPath(String pointer) {
    return enabled && paths.contains(pointer);
  }

  public TextSanitizer text() {
    return text;
  }

  private static boolean validPointer(String path) {
    if (!path.isEmpty() && path.charAt(0) != '/') {
      return false;
    }
    for (int i = 0; i < path.length(); i++) {
      if (path.charAt(i) == '~') {
        if (++i == path.length() || (path.charAt(i) != '0' && path.charAt(i) != '1')) {
          return false;
        }
      }
    }
    return true;
  }
}
