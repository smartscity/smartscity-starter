package com.smartscity.observability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Immutable Aho-Corasick matcher retaining only the longest suffix at each state. Overlapping
 * matches redact their union without materializing a match list.
 *
 * @author jack
 */
public final class LiteralMasker {
  private final List<Node> nodes = new ArrayList<>();
  private final int maxInput;

  /**
   * Compiles a bounded dictionary once; callers may share the resulting matcher.
   *
   * @param words literal sensitive values, not field names or regexes
   * @param maxInput maximum input UTF-16 code units
   * @throws IllegalArgumentException if the dictionary or input limit exceeds its budget
   */
  public LiteralMasker(Collection<String> words, int maxInput) {
    if (maxInput < 1 || maxInput > 1_048_576 || words.size() > 4096) {
      throw new IllegalArgumentException("Masking budget exceeded");
    }
    this.maxInput = maxInput;
    nodes.add(new Node());
    int total = 0;
    for (String w : words) {
      if (w == null || w.isEmpty() || w.length() > 4096 || (total += w.length()) > 65536) {
        throw new IllegalArgumentException("Invalid literal dictionary");
      }
      int s = 0;
      for (char c : w.toCharArray()) {
        Integer n = nodes.get(s).next.get(c);
        if (n == null) {
          n = nodes.size();
          nodes.get(s).next.put(c, n);
          nodes.add(new Node());
        }
        s = n;
      }
      nodes.get(s).length = Math.max(nodes.get(s).length, w.length());
    }
    Queue<Integer> q = new ArrayDeque<>();
    q.addAll(nodes.get(0).next.values());
    while (!q.isEmpty()) {
      int state = q.remove();
      for (Map.Entry<Character, Integer> e : nodes.get(state).next.entrySet()) {
        int f = nodes.get(state).fail;
        char c = e.getKey();
        while (f != 0 && !nodes.get(f).next.containsKey(c)) {
          f = nodes.get(f).fail;
        }
        Integer next = nodes.get(f).next.get(c);
        Node child = nodes.get(e.getValue());
        child.fail = next == null ? 0 : next;
        child.length = Math.max(child.length, nodes.get(child.fail).length);
        q.add(e.getValue());
      }
    }
  }

  /**
   * Redacts every covered range, including overlapping dictionary entries.
   *
   * @param text formatted text, nullable
   * @return sanitized text or a safe omission marker on oversized input
   */
  public String mask(String text) {
    if (text == null) {
      return null;
    }
    if (text.length() > maxInput) {
      return "[omitted:limit]";
    }
    RedactionRanges ranges = new RedactionRanges(text);
    cover(ranges);
    return ranges.render();
  }

  /**
   * Adds literal matches over the unchanged source to an invocation-owned range set.
   *
   * @param ranges non-null, thread-confined ranges shared by the policies of one call
   * @throws IllegalArgumentException when its source exceeds this matcher's input limit
   */
  public void cover(RedactionRanges ranges) {
    String text = ranges.source();
    if (text.length() > maxInput) {
      throw new IllegalArgumentException("Masking input limit");
    }
    if (nodes.size() == 1) {
      return;
    }
    int state = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      while (state != 0 && !nodes.get(state).next.containsKey(c)) {
        state = nodes.get(state).fail;
      }
      state = nodes.get(state).next.getOrDefault(c, 0);
      int length = nodes.get(state).length;
      if (length > 0) {
        ranges.cover(i - length + 1, i + 1);
      }
    }
  }

  private static final class Node {
    private final Map<Character, Integer> next = new HashMap<>();
    private int fail;
    private int length;
  }
}
