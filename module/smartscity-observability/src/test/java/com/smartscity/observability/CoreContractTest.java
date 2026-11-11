package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for identities, overlap masking and the physical log grammar.
 *
 * @author jack
 */
class CoreContractTest {
  @Test
  void parentChildAndConcurrentBranches() {
    CorrelationContext a = CorrelationContext.root("a");
    CorrelationContext ab = a.child("a", "b");
    CorrelationContext ad = a.child("a", "d");
    CorrelationContext b = CorrelationContext.receive(ab.traceparent(), "a", "b");
    assertNull(a.parentId());
    assertEquals(a.currentId(), ab.parentId());
    assertEquals(ab.currentId(), b.parentId());
    assertEquals(a.traceId(), b.traceId());
    assertNotEquals(ab.currentId(), b.currentId());
    assertNotEquals(ab.currentId(), ad.currentId());
    assertTrue(CorrelationContext.validHeader(ab.traceparent()));
  }

  @Test
  void invalidHeadersCannotSupplyIdentity() {
    String valid = CorrelationContext.root("a").traceparent();
    assertFalse(CorrelationContext.validHeader(valid.toUpperCase()));
    assertFalse(
        CorrelationContext.validHeader("00-00000000000000000000000000000000-0000000000000000-01"));
    assertFalse(CorrelationContext.validHeader(valid + "\n"));
    assertNotEquals(
        valid.substring(3, 35), CorrelationContext.receive(valid + "\n", "x", "b").traceId());
  }

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

  @Test
  void wireIsSingleLineAndEscapesOnlyValues() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("traceid", "abc");
    fields.put("errmsg", null);
    fields.put("response", "a||b%7C\n\u4e2d=1");
    String out =
        new PipeLogEncoder(1024)
            .encode("INFO", Instant.EPOCH, "A.java:1", "_com_request_out", fields);
    assertTrue(out.contains("||errmsg=null||response=a%7C%7Cb%257C%0A\u4e2d=1"));
    assertEquals(1, out.chars().filter(c -> c == '\n').count());
    assertEquals("%6Eull", PipeLogEncoder.escape("null"));
    assertFalse(out.contains("v=1"));
  }

  @Test
  void recordLimitNeverCutsEscapes() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("traceid", "x");
    f.put("response", String.join("", Collections.nCopies(1000, "|")));
    String line = new PipeLogEncoder(1024).encode("INFO", Instant.EPOCH, "a:1", "_undef", f);
    assertTrue(line.contains("[omitted:record-limit]"));
    assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024);
  }

  @Test
  void budgetOverflowAndReleaseAreExact() {
    CaptureBudget b = new CaptureBudget(4);
    CaptureBudget.Capture c = b.reserve(4);
    assertNull(b.reserve(1));
    c.append(new byte[5], 0, 5);
    assertTrue(c.overflow());
    assertNull(c.snapshot());
    c.close();
    c.close();
    assertEquals(0, b.reservedBytes());
    assertNotNull(b.reserve(4));
  }
}
