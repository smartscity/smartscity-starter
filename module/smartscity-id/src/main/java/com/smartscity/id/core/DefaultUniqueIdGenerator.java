package com.smartscity.id.core;

import com.smartscity.id.api.IdException;

/**
 * Single owner: only the cache producer may advance this sequence.
 *
 * @author jack
 */
public final class DefaultUniqueIdGenerator {
  private final BitsAllocator bits;
  private final long worker;
  private long second = -1;
  private long nextSequence;

  /**
   * Creates a single-owner sequence cursor for an already allocated worker.
   *
   * @param bits immutable encoding format
   * @param worker externally allocated worker number
   * @throws IllegalArgumentException if worker is outside the layout's worker space
   */
  public DefaultUniqueIdGenerator(BitsAllocator bits, long worker) {
    if (worker < 0 || worker >= bits.getWorkerSlots()) {
      throw new IllegalArgumentException(
          "workerId=" + worker + " must be in [0, " + bits.getWorkerSlots() + ")");
    }
    this.bits = bits;
    this.worker = worker;
  }

  /** Advances the producer's logical time when its current sequence has been consumed. */
  long nextBuffered(long nowSecond) {
    if (nowSecond < 0 || nowSecond > bits.getMaxTime()) {
      throw new IdException(IdException.Reason.CAPACITY, "Wall time outside identity layout");
    }
    long logicalSecond = Math.max(nowSecond, second);
    if (logicalSecond == second && nextSequence > bits.getMaxSequence()) {
      if (second == bits.getMaxTime()) {
        throw new IdException(IdException.Reason.CAPACITY, "Logical timestamp range exhausted");
      }
      logicalSecond++;
    }
    return next(logicalSecond);
  }

  /**
   * Advances the sequence within the supplied second without borrowing future time.
   *
   * @param nowSecond elapsed whole seconds since the configured epoch
   * @return a positive ID, or zero when this second's sequence space is exhausted
   * @throws IdException if time regresses or exceeds the layout's timestamp range
   */
  public long next(long nowSecond) {
    if (nowSecond < 0 || nowSecond > bits.getMaxTime()) {
      throw new IdException(
          IdException.Reason.CAPACITY,
          "Timestamp outside layout: workerId="
              + worker
              + ", currentSecond="
              + nowSecond
              + ", maxSecond="
              + bits.getMaxTime());
    }
    if (nowSecond < second) {
      throw new IdException(
          IdException.Reason.CLOCK,
          "Time regressed: workerId="
              + worker
              + ", lastSecond="
              + second
              + ", currentSecond="
              + nowSecond);
    }
    if (nowSecond > second) {
      second = nowSecond;
      nextSequence = 0;
    }
    if (second == 0 && worker == 0 && nextSequence == 0) {
      nextSequence = 1;
    }
    if (nextSequence > bits.getMaxSequence()) {
      return 0;
    }
    return bits.allocate(second, worker, nextSequence++);
  }
}
