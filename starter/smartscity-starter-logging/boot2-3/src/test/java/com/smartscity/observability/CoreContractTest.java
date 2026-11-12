package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
