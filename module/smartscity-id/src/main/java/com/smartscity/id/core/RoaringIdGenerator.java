package com.smartscity.id.core;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.api.IdParts;
import com.smartscity.id.api.IdSnapshot;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-owning decorator for a standard 30/18/15 generator. No allocation resource is created.
 * Closing this view rejects new calls but never closes its application-owned delegate. Metrics
 * describe delegate allocation, excluding permutation and view-closed failures.
 *
 * @author jack
 */
public final class RoaringIdGenerator implements IdGenerator {
  private final IdGenerator delegate;
  private final RoaringIdEncoder encoder;
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Creates a non-owning view. The caller must ensure the delegate emits standard 30/18/15 IDs; an
   * arbitrary IdGenerator cannot expose or prove its layout. Do not wrap an encoded view.
   *
   * @param delegate application-owned standard generator, non-null
   * @param encoder versioned stateless encoder, non-null
   * @throws NullPointerException if either argument is null
   */
  public RoaringIdGenerator(IdGenerator delegate, RoaringIdEncoder encoder) {
    this.delegate = Objects.requireNonNull(delegate);
    this.encoder = Objects.requireNonNull(encoder);
    IdLayout layout =
        delegate instanceof BufferedIdGenerator
            ? ((BufferedIdGenerator) delegate).layout()
            : delegate instanceof SequenceIdGenerator
                ? ((SequenceIdGenerator) delegate).layout()
                : null;
    if (layout != null
        && (layout.getTimestampBits() != 30
            || layout.getWorkerBits() != 18
            || layout.getSequenceBits() != 15)) {
      throw new IllegalArgumentException("Roaring encoding requires 30/18/15");
    }
    if (delegate instanceof RoaringIdGenerator) {
      throw new IllegalArgumentException("Already encoded delegate");
    }
  }

  @Override
  public long getUniqueId() {
    if (closed.get()) {
      throw new IdException(IdException.Reason.CLOSED, "Roaring view closed");
    }
    return encoder.encode(delegate.getUniqueId());
  }

  @Override
  public IdParts parse(long id) {
    return delegate.parse(encoder.decode(id));
  }

  /** Returns delegate observation; readiness is allocation readiness, not view openness. */
  @Override
  public IdSnapshot snapshot() {
    return delegate.snapshot();
  }

  @Override
  public void close() {
    closed.set(true);
  }
}
