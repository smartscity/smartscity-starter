package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdParts;
import java.time.Instant;

/**
 * Immutable encoding contract. Values are checked before shifts, never truncated.
 *
 * @author jack
 */
public final class BitsAllocator {
  /**
   * Default epoch retained for source compatibility with fixed-format callers.
   *
   * @deprecated Use {@code getLayout().getEpoch()} for the configured instance epoch.
   */
  @Deprecated public static final Instant EPOCH = IdLayout.DEFAULT_EPOCH;

  private final IdLayout layout;
  private final int timeBits;
  private final int workerBits;
  private final int sequenceBits;

  private final long maxTime;
  private final long maxWorker;
  private final long maxSequence;

  /**
   * Creates an allocator using the legacy default epoch.
   *
   * @param timeBits width of elapsed seconds
   * @param workerBits width of the externally allocated worker number
   * @param sequenceBits width of the sequence within a second
   * @throws IdException if the widths cannot form a supported layout
   */
  public BitsAllocator(int timeBits, int workerBits, int sequenceBits) {
    this(new IdLayout(IdLayout.DEFAULT_EPOCH, timeBits, workerBits, sequenceBits));
  }

  /**
   * Creates an allocator bound to one immutable identity format.
   *
   * @param layout validated epoch and widths
   * @throws NullPointerException if layout is null
   */
  public BitsAllocator(IdLayout layout) {
    this.layout = java.util.Objects.requireNonNull(layout, "layout");
    timeBits = layout.getTimestampBits();
    workerBits = layout.getWorkerBits();
    sequenceBits = layout.getSequenceBits();
    maxTime = (1L << timeBits) - 1;
    maxWorker = (1L << workerBits) - 1;
    maxSequence = (1L << sequenceBits) - 1;
  }

  public IdLayout getLayout() {
    return layout;
  }

  /**
   * Selects the 30/18/15 timestamp/worker/sequence preset.
   *
   * @return an immutable allocator using the default 2026 epoch
   */
  public static BitsAllocator standard() {
    return new BitsAllocator(IdLayout.standard());
  }

  /**
   * Selects the 30/16/17 timestamp/worker/sequence preset.
   *
   * @return an immutable allocator using the default 2026 epoch
   */
  public static BitsAllocator highThroughput() {
    return new BitsAllocator(IdLayout.highThroughput());
  }

  /**
   * Returns a 53-bit allocator with ten worker bits and the default epoch.
   *
   * @param sequenceBits sequence width from 15 through 17
   * @return an allocator whose timestamp width is 43 minus sequenceBits
   * @throws IdException if sequenceBits is outside the supported range
   */
  public static BitsAllocator javascript(int sequenceBits) {
    return new BitsAllocator(IdLayout.javascript(sequenceBits));
  }

  /**
   * Encodes validated fields without truncation or wraparound.
   *
   * @param seconds elapsed whole seconds since this layout's epoch
   * @param worker allocated worker number within the configured width
   * @param sequence sequence within the given second
   * @return a positive ID encoded with this instance's layout
   * @throws IdException if a field is out of range or the encoded ID is reserved zero
   */
  public long allocate(long seconds, long worker, long sequence) {
    if (seconds < 0
        || seconds > maxTime
        || worker < 0
        || worker > maxWorker
        || sequence < 0
        || sequence > maxSequence) {
      throw new IdException(IdException.Reason.CAPACITY, "ID field exceeds its range");
    }
    long id = (seconds << (workerBits + sequenceBits)) | (worker << sequenceBits) | sequence;
    if (id == 0) {
      throw new IdException(IdException.Reason.CAPACITY, "ID zero is reserved");
    }
    return id;
  }

  /**
   * Decodes an ID using this allocator's epoch and bit widths.
   *
   * @param id positive ID whose external format matches this allocator
   * @return timestamp, worker and sequence fields
   * @throws IdException if the ID is nonpositive or exceeds the layout's total width
   */
  public IdParts parse(long id) {
    if (id <= 0
        || (timeBits + workerBits + sequenceBits < 63
            && (id >>> (timeBits + workerBits + sequenceBits)) != 0)) {
      throw new IdException(IdException.Reason.CONFIGURATION, "ID does not fit this layout");
    }
    return new IdParts(
        layout.getEpoch().plusSeconds(id >>> (workerBits + sequenceBits)),
        (id >>> sequenceBits) & maxWorker,
        id & maxSequence);
  }

  public int getTimeBits() {
    return timeBits;
  }

  public int getWorkerBits() {
    return workerBits;
  }

  public int getSequenceBits() {
    return sequenceBits;
  }

  public long getMaxTime() {
    return maxTime;
  }

  public long getMaxSequence() {
    return maxSequence;
  }

  public long getWorkerSlots() {
    return maxWorker + 1;
  }

  /**
   * Describes the bit widths for configuration comparison and diagnostics.
   *
   * @return timestamp/worker/sequence widths, excluding the epoch
   */
  public String signature() {
    return timeBits + "/" + workerBits + "/" + sequenceBits;
  }
}
