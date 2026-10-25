package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import java.time.DateTimeException;
import java.time.Instant;

/**
 * Immutable identity format, shared by encoding, decoding and worker registration. Changing the
 * epoch or widths requires a new externally separated ID namespace.
 *
 * @author jack
 */
public final class IdLayout {
  /** Default used only when callers do not configure an instance epoch. */
  public static final Instant DEFAULT_EPOCH = Instant.parse("2026-01-01T00:00:00Z");

  private final Instant epoch;
  private final int timestampBits;
  private final int workerBits;
  private final int sequenceBits;

  /**
   * Creates a format whose full timestamp range can be represented in milliseconds.
   *
   * @param epoch non-null UTC instant aligned to a whole second
   * @param timestampBits positive elapsed-second width
   * @param workerBits positive worker-number width
   * @param sequenceBits positive sequence width; the three widths must total at most 63
   * @throws IdException if the epoch, widths or resulting time range are invalid
   */
  public IdLayout(Instant epoch, int timestampBits, int workerBits, int sequenceBits) {
    if (timestampBits < 1
        || workerBits < 1
        || sequenceBits < 1
        || timestampBits > 61
        || workerBits > 61
        || sequenceBits > 61
        || timestampBits + workerBits + sequenceBits > 63) {
      throw new IdException(
          IdException.Reason.CONFIGURATION,
          "ID bits must each be 1..61 and timestamp + worker + sequence must not exceed 63");
    }
    if (epoch == null || epoch.getNano() != 0) {
      throw new IdException(
          IdException.Reason.CONFIGURATION,
          "ID epoch must be a non-null ISO-8601 instant aligned to a whole second");
    }
    try {
      epoch.toEpochMilli();
      epoch.plusSeconds((1L << timestampBits) - 1).toEpochMilli();
    } catch (ArithmeticException | DateTimeException e) {
      throw new IdException(
          IdException.Reason.CONFIGURATION,
          "ID epoch and timestamp bits exceed the supported millisecond timestamp range",
          e);
    }
    this.epoch = epoch;
    this.timestampBits = timestampBits;
    this.workerBits = workerBits;
    this.sequenceBits = sequenceBits;
  }

  /**
   * Selects the 30/18/15 timestamp/worker/sequence preset.
   *
   * @return an immutable format using the default 2026 epoch
   */
  public static IdLayout standard() {
    return new IdLayout(DEFAULT_EPOCH, 30, 18, 15);
  }

  /**
   * Selects the 30/16/17 timestamp/worker/sequence preset.
   *
   * @return an immutable format using the default 2026 epoch
   */
  public static IdLayout highThroughput() {
    return new IdLayout(DEFAULT_EPOCH, 30, 16, 17);
  }

  /**
   * Returns a format that fits exactly within JavaScript's 53-bit integer precision.
   *
   * @param sequenceBits sequence width from 15 through 17
   * @return a format with ten worker bits and 43 minus sequenceBits timestamp bits
   * @throws IdException if the sequence width is unsupported
   */
  public static IdLayout javascript(int sequenceBits) {
    if (sequenceBits < 15 || sequenceBits > 17) {
      throw new IdException(IdException.Reason.CONFIGURATION, "JS sequence bits must be 15..17");
    }
    return new IdLayout(DEFAULT_EPOCH, 43 - sequenceBits, 10, sequenceBits);
  }

  public Instant getEpoch() {
    return epoch;
  }

  public int getTimestampBits() {
    return timestampBits;
  }

  public int getWorkerBits() {
    return workerBits;
  }

  public int getSequenceBits() {
    return sequenceBits;
  }
}
