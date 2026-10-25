package com.smartscity.id.api;

import java.time.Instant;

/**
 * Decoded timestamp, worker and sequence values interpreted using one external ID format.
 *
 * @author jack
 */
public final class IdParts {
  private final Instant timestamp;
  private final long workerId;
  private final long sequence;

  /**
   * Captures decoded fields without validating their source format.
   *
   * @param timestamp decoded instant; retained as supplied, including null
   * @param workerId decoded worker number; range validation belongs to the decoder
   * @param sequence decoded sequence; range validation belongs to the decoder
   */
  public IdParts(Instant timestamp, long workerId, long sequence) {
    this.timestamp = timestamp;
    this.workerId = workerId;
    this.sequence = sequence;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public long getWorkerId() {
    return workerId;
  }

  public long getSequence() {
    return sequence;
  }
}
