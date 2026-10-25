package com.smartscity.id.api;

import java.util.Objects;

/**
 * Typed ID failure with a stable machine code and optional operation-specific details. The original
 * cause is retained; callers should branch on reason/code, never message text.
 *
 * @author jack
 */
public final class IdException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Stable reasons; codes must not be reassigned to different failures. */
  public enum Reason {
    CONFIGURATION("ID-001", "Invalid ID generator configuration"),
    STARTUP("ID-002", "ID generator initialization failed"),
    CLOCK("ID-003", "Clock validation failed"),
    CAPACITY("ID-004", "ID generation capacity exhausted"),
    TIMEOUT("ID-005", "ID generation operation timed out"),
    INTERRUPTED("ID-006", "ID generation was interrupted"),
    COORDINATION("ID-007", "Worker coordination failed"),
    CLOSED("ID-008", "ID generator is closed"),
    INTERNAL("ID-999", "Unexpected ID generator failure");

    private final String code;
    private final String message;

    Reason(String code, String message) {
      this.code = code;
      this.message = message;
    }

    public String code() {
      return code;
    }

    public String message() {
      return message;
    }
  }

  private final Reason reason;

  /**
   * Creates a failure with only the stable reason description.
   *
   * @param reason stable failure classification
   * @throws NullPointerException if reason is null
   */
  public IdException(Reason reason) {
    this(reason, null, null);
  }

  /**
   * Creates a failure with operation context and no nested cause.
   *
   * @param reason stable failure classification
   * @param details optional context; must not contain credentials
   * @throws NullPointerException if reason is null
   */
  public IdException(Reason reason, String details) {
    this(reason, details, null);
  }

  /**
   * Creates a typed failure without replacing or flattening its original cause.
   *
   * @param reason non-null stable failure classification
   * @param details optional context; must not contain credentials
   * @param cause original failure, or null
   * @throws NullPointerException if reason is null
   */
  public IdException(Reason reason, String details, Throwable cause) {
    super(details, cause);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason getReason() {
    return reason;
  }

  public String getCode() {
    return reason.code();
  }

  public String getDefaultMessage() {
    return reason.message();
  }

  public String getDetails() {
    return super.getMessage();
  }

  /**
   * Formats the stable code, default description and optional operation context on demand.
   *
   * @return the diagnostic message, excluding the nested cause's message
   */
  @Override
  public String getMessage() {
    String details = getDetails();
    return "["
        + getCode()
        + "] "
        + getDefaultMessage()
        + (details == null || details.isEmpty() ? "" : ": " + details);
  }
}
