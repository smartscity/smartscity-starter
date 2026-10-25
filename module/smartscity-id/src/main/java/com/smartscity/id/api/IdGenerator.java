package com.smartscity.id.api;

/**
 * One SDK instance owns one worker. Concurrent completion order is not ID order.
 *
 * @author jack
 */
public interface IdGenerator extends AutoCloseable {
  /**
   * Claims one ID under the configured worker-ownership assumptions. Supports concurrent callers;
   * completion order need not match ID order.
   *
   * <p>Cached implementations validate time in the producer and reject its published terminal
   * failures. A cached claim does not imply a fresh wall-clock sample; an undetected clock fault
   * may coexist with successful cached issuance until the producer next validates time.
   *
   * @return a positive ID in this generator's namespace
   * @throws IdException on closure, invalid clock/lease, exhaustion timeout or interruption
   */
  long getUniqueId();

  /**
   * Decodes using this instance's immutable format; an ID does not carry a format version.
   *
   * @param id positive ID encoded with this instance's format
   * @return the decoded timestamp, worker number and sequence
   * @throws IdException if the ID does not fit the configured layout
   */
  IdParts parse(long id);

  /**
   * Observes generation and readiness without changing either state.
   *
   * <p>Safe to call concurrently with generation or shutdown. Fields are approximate observations,
   * not an atomic checkpoint or a promise that the next request will succeed.
   *
   * @return a read-only snapshot of locally observed state and counters
   */
  IdSnapshot snapshot();

  /**
   * Stops production and releases owned resources.
   *
   * <p>Repeated calls are harmless. Concurrent callers may observe closure; an in-flight call
   * cannot be atomically withdrawn. Implementations retain their own bounded cleanup budgets.
   * Applications must not reuse the generator's worker allocation after shutdown.
   */
  @Override
  void close();
}
