package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for identities, overlap masking and the physical log grammar.
 *
 * @author jack
 */
class CoreContractTest {
  @Test
  void overlappingMatchesRedactUnion() {
    assertEquals("x***z", new LiteralMasker(Arrays.asList("abc", "bcd", "c"), 100).mask("xabcdz"));
    assertEquals("[omitted:limit]", new LiteralMasker(Collections.emptyList(), 1).mask("ab"));
  }

  @Test
  void randomizedMatcherMatchesCoverageOracle() {
    java.util.Random r = new java.util.Random(31);
    for (int n = 0; n < 300; n++) {
      java.util.List<String> words = new java.util.ArrayList<>();
      for (int w = 0; w < 8; w++) {
        StringBuilder x = new StringBuilder();
        for (int k = 0; k < 1 + r.nextInt(5); k++) {
          x.append((char) ('a' + r.nextInt(3)));
        }
        words.add(x.toString());
      }
      StringBuilder input = new StringBuilder();
      for (int k = 0; k < 100; k++) {
        input.append((char) ('a' + r.nextInt(4)));
      }
      boolean[] covered = new boolean[100];
      for (String w : words) {
        int from = 0, index;
        while ((index = input.indexOf(w, from)) >= 0) {
          Arrays.fill(covered, index, index + w.length(), true);
          from = index + 1;
        }
      }
      StringBuilder expected = new StringBuilder();
      for (int k = 0; k < 100; k++) {
        if (covered[k]) {
          if (k == 0 || !covered[k - 1]) {
            expected.append("***");
          }
        } else {
          expected.append(input.charAt(k));
        }
      }
      assertEquals(expected.toString(), new LiteralMasker(words, 100).mask(input.toString()));
    }
  }
}
