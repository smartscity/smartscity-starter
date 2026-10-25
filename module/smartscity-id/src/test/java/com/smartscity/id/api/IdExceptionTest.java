package com.smartscity.id.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Protects stable reason codes and the separation between diagnostic details and original causes.
 *
 * @author jack
 */
class IdExceptionTest {
  @Test
  void stableCodesAndDefaultMessages() {
    String[] codes = {
      "ID-001", "ID-002", "ID-003", "ID-004", "ID-005", "ID-006", "ID-007", "ID-008", "ID-999"
    };
    Set<String> unique = new HashSet<>();
    for (int i = 0; i < codes.length; i++) {
      IdException.Reason reason = IdException.Reason.values()[i];
      IdException failure = new IdException(reason);
      assertEquals(codes[i], failure.getCode());
      assertTrue(unique.add(failure.getCode()));
      assertSame(reason, failure.getReason());
      assertEquals(reason.message(), failure.getDefaultMessage());
      assertEquals("[" + codes[i] + "] " + reason.message(), failure.getMessage());
      assertNull(failure.getDetails());
    }
  }

  @Test
  void contextAndCauseRemainDistinct() {
    Throwable cause = new IllegalStateException("original driver failure");
    IdException failure =
        new IdException(IdException.Reason.COORDINATION, "registrationId=42", cause);
    assertSame(cause, failure.getCause());
    assertEquals("registrationId=42", failure.getDetails());
    assertEquals("[ID-007] Worker coordination failed: registrationId=42", failure.getMessage());
  }
}
