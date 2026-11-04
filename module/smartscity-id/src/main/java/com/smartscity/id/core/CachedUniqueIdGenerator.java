package com.smartscity.id.core;

import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdParts;
import com.smartscity.id.api.IdSnapshot;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.spi.WorkerLease;

/**
 * Source and binary compatible adapter for the former buffered generator name.
 *
 * @deprecated Use {@link BufferedIdGenerator}; ownership and issuance semantics are unchanged.
 * @author jack
 */
@Deprecated
public final class CachedUniqueIdGenerator implements IdGenerator {
  private final BufferedIdGenerator delegate;

  /** Transfers the supplied lease to the buffered implementation. */
  public CachedUniqueIdGenerator(GeneratorOptions options, WorkerLease lease, TimeSource time) {
    delegate = new BufferedIdGenerator(options, lease, time);
  }

  private CachedUniqueIdGenerator(BufferedIdGenerator delegate) {
    this.delegate = delegate;
  }

  /** Uses an externally calibrated clock and transfers the supplied lease. */
  public static CachedUniqueIdGenerator usingClock(
      GeneratorOptions options, WorkerLease lease, MonotonicClock clock) {
    return new CachedUniqueIdGenerator(BufferedIdGenerator.usingClock(options, lease, clock));
  }

  @Override
  public long getUniqueId() {
    return delegate.getUniqueId();
  }

  @Override
  public IdParts parse(long id) {
    return delegate.parse(id);
  }

  @Override
  public IdSnapshot snapshot() {
    return delegate.snapshot();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
