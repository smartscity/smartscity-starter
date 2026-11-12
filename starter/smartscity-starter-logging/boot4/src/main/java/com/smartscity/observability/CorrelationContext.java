package com.smartscity.observability;

import java.security.SecureRandom;

/**
 * Immutable parent/current identities; scopes, never this value, own thread attachment.
 *
 * @author jack
 */
public final class CorrelationContext {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final String traceId;
  private final String parentId;
  private final String currentId;
  private final String flags;
  private final String caller;
  private final String called;

  private CorrelationContext(
      String trace, String parent, String current, String flags, String caller, String called) {
    this.traceId = trace;
    this.parentId = parent;
    this.currentId = current;
    this.flags = flags;
    this.caller = caller;
    this.called = called;
  }

  /**
   * Creates a fresh root without a worker lease or external allocation service.
   *
   * @param service local logical service name
   * @return a root with no parent
   */
  public static CorrelationContext root(String service) {
    return new CorrelationContext(id(16), null, id(8), "00", "unknown", service);
  }

  /**
   * Parses a version-zero W3C header and creates a distinct server child.
   *
   * @param header incoming header, nullable; invalid headers start a new trace
   * @param caller validated caller label
   * @param service local service
   * @return a new server context
   */
  public static CorrelationContext receive(String header, String caller, String service) {
    if (!validHeader(header)) {
      return new CorrelationContext(id(16), null, id(8), "00", caller, service);
    }
    return new CorrelationContext(
        header.substring(3, 35),
        header.substring(36, 52),
        id(8),
        header.substring(53, 55),
        caller,
        service);
  }

  /**
   * Adapts an existing provider without generating a parallel trace.
   *
   * @param trace nonzero lowercase trace ID
   * @param parent parent ID or null for an unavailable/root parent
   * @param current nonzero current ID
   * @param sampled whether the provider records the trace
   * @param caller upstream label
   * @param called current operation target
   * @return a validated context
   * @throws IllegalArgumentException if identifiers are malformed
   */
  public static CorrelationContext existing(
      String trace, String parent, String current, boolean sampled, String caller, String called) {
    if (!hex(trace, 32) || !hex(current, 16) || (parent != null && !hex(parent, 16))) {
      throw new IllegalArgumentException("Invalid provider trace identifiers");
    }
    return new CorrelationContext(trace, parent, current, sampled ? "01" : "00", caller, called);
  }

  /**
   * Creates one child per actual invocation/attempt.
   *
   * @param service invoking service
   * @param target destination label
   * @return independent child; this context remains unchanged
   */
  public CorrelationContext child(String service, String target) {
    return new CorrelationContext(traceId, currentId, id(8), flags, service, target);
  }

  public String traceId() {
    return traceId;
  }

  public String parentId() {
    return parentId;
  }

  public String currentId() {
    return currentId;
  }

  public String caller() {
    return caller;
  }

  public String called() {
    return called;
  }

  public String traceparent() {
    return "00-" + traceId + "-" + currentId + "-" + flags;
  }

  /**
   * Checks supported version-zero headers without accepting zero IDs or uppercase hex.
   *
   * @param h untrusted header, nullable
   * @return whether the complete header is valid
   */
  public static boolean validHeader(String h) {
    return h != null
        && h.length() == 55
        && h.startsWith("00-")
        && h.charAt(35) == '-'
        && h.charAt(52) == '-'
        && hex(h.substring(3, 35), 32)
        && hex(h.substring(36, 52), 16)
        && h.substring(53).matches("[0-9a-f]{2}");
  }

  private static boolean hex(String v, int length) {
    return v != null && v.length() == length && v.matches("[0-9a-f]+") && !v.matches("0+");
  }

  private static String id(int bytes) {
    byte[] b = new byte[bytes];
    StringBuilder out;
    do {
      RANDOM.nextBytes(b);
      out = new StringBuilder(bytes * 2);
      for (byte x : b) {
        out.append(Character.forDigit((x >>> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
      }
    } while (out.toString().matches("0+"));
    return out.toString();
  }
}
