package com.smartscity.id.api;

/**
 * Immutable registry capacity observation, never permission to reuse a worker.
 *
 * <p>Remaining slots are an upper bound after the observation instant: other processes may register
 * immediately afterward. A failed refresh retains the last known values and timestamp.
 *
 * @author jack
 */
public final class RegistryCapacity {
  /** Origin and freshness outcome of a capacity observation. */
  public enum Status {
    UNKNOWN,
    REGISTERED,
    OBSERVED,
    FAILED
  }

  private static final RegistryCapacity UNKNOWN = new RegistryCapacity(-1, -1, 0, Status.UNKNOWN);
  private final long slots;
  private final long remaining;
  private final long observedAtMillis;
  private final Status status;

  private RegistryCapacity(long slots, long remaining, long observedAtMillis, Status status) {
    this.slots = slots;
    this.remaining = remaining;
    this.observedAtMillis = observedAtMillis;
    this.status = status;
  }

  /**
   * Creates an observation of a committed allocation cursor.
   *
   * @param slots positive lifetime worker capacity
   * @param remaining unused workers at observation, between zero and slots
   * @param observedAtMillis UTC milliseconds at the start of the observation
   * @param status REGISTERED for a registration receipt or OBSERVED for a read-only query
   * @return immutable observation
   * @throws IllegalArgumentException for invalid capacity or status
   */
  public static RegistryCapacity observed(
      long slots, long remaining, long observedAtMillis, Status status) {
    if (slots <= 0
        || remaining < 0
        || remaining > slots
        || (status != Status.REGISTERED && status != Status.OBSERVED)) {
      throw new IllegalArgumentException("Invalid registry capacity observation");
    }
    return new RegistryCapacity(slots, remaining, observedAtMillis, status);
  }

  public static RegistryCapacity unknown() {
    return UNKNOWN;
  }

  /** Returns the last observation marked failed without refreshing its timestamp. */
  public RegistryCapacity failed() {
    return new RegistryCapacity(slots, remaining, observedAtMillis, Status.FAILED);
  }

  public long getSlots() {
    return slots;
  }

  public long getRemaining() {
    return remaining;
  }

  public long getObservedAtMillis() {
    return observedAtMillis;
  }

  public Status getStatus() {
    return status;
  }
}
