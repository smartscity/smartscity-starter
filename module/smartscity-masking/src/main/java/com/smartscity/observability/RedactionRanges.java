package com.smartscity.observability;

import java.util.Objects;

/**
 * Owns the union of redaction intervals over one immutable original string. All policies inspect
 * the same source; rendering happens after matching so one replacement cannot hide another match.
 * Instances are confined to a single sanitization call; compiled matchers remain shareable.
 *
 * @author jack
 */
public final class RedactionRanges {
  private final String source;
  private int[] differences;

  /**
   * Creates an empty range set without allocating a coverage array.
   *
   * @param source original text, non-null, at most one million UTF-16 code units
   * @throws IllegalArgumentException when the source exceeds the memory bound
   */
  public RedactionRanges(String source) {
    this.source = Objects.requireNonNull(source, "source");
    if (source.length() > 1_048_576) {
      throw new IllegalArgumentException("Redaction input limit");
    }
  }

  public String source() {
    return source;
  }

  /**
   * Adds a half-open interval; overlaps and adjacent ranges are rendered as one replacement.
   *
   * @param start inclusive source offset
   * @param end exclusive source offset
   * @throws IllegalArgumentException for invalid offsets
   */
  public void cover(int start, int end) {
    if (start < 0 || end < start || end > source.length()) {
      throw new IllegalArgumentException("Invalid redaction range");
    }
    if (start == end) {
      return;
    }
    if (differences == null) {
      differences = new int[source.length() + 1];
    }
    differences[start]++;
    differences[end]--;
  }

  /**
   * Renders the union without changing the source or ranges.
   *
   * @return original string when no ranges exist; otherwise a redacted copy
   */
  public String render() {
    if (differences == null) {
      return source;
    }
    StringBuilder out = new StringBuilder(source.length());
    int covered = 0;
    boolean redacting = false;
    for (int i = 0; i < source.length(); i++) {
      covered += differences[i];
      if (covered > 0) {
        if (!redacting) {
          out.append("***");
        }
        redacting = true;
      } else {
        out.append(source.charAt(i));
        redacting = false;
      }
    }
    return out.toString();
  }
}
