package com.smartscity.id.spi;

import com.smartscity.id.api.RegistryCapacity;

/**
 * Local observation only: state() must never perform network I/O.
 *
 * @author jack
 */
public interface WorkerLease extends AutoCloseable {
  /** Locally observed issuance permission; expiry is terminal for this generator. */
  enum State {
    ACTIVE,
    SUSPENDED,
    EXPIRED,
    CLOSED
  }

  /**
   * Identifies the external registration for diagnostics.
   *
   * @return registration identity, which is not the encoded worker number
   */
  long rawId();

  /**
   * Identifies the worker reserved exclusively for this generator.
   *
   * @return nonnegative worker number fitting the configured worker width
   */
  long workerId();

  /**
   * Observes permission to issue IDs from local coordination state.
   *
   * @return current state, obtained through a bounded, thread-safe read without remote I/O
   */
  State state();

  /**
   * Reads the last capacity observation without blocking on remote I/O.
   *
   * <p>Adapters may request a bounded asynchronous refresh. This method is used only for
   * observation, never issuance admission. Unknown capacity must not be reported as zero available
   * slots.
   *
   * @return an immutable, possibly stale observation; unknown for allocators without monitoring
   */
  default RegistryCapacity capacity() {
    return RegistryCapacity.unknown();
  }

  /**
   * Releases owned coordination resources without making the worker number reusable.
   *
   * <p>Implementations must tolerate repeated calls and concurrent local state observation.
   */
  @Override
  void close();
}
