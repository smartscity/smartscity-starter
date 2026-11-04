package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.WorkerLease;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that rejected or late claims still notify refill after releasing their ring slots.
 *
 * @author jack
 */
@Timeout(5)
class BurnedClaimRefillTest {
  @Test
  void postClaimAdmissionFailureDoesNotSkipTheRefillSignal() throws Exception {
    ObservedLease lease = new ObservedLease();
    try (PausedProducerTime paused = new PausedProducerTime(new ControlledTime());
        BufferedIdGenerator generator =
            new BufferedIdGenerator(
                GeneratorOptions.builder().capacity(2).build(), lease, paused)) {
      try {
        paused.awaitPaused();
        AtomicBoolean parked = field(generator, "producerParked", AtomicBoolean.class);
        lease.checks.set(0);
        lease.onCheck =
            count -> {
              if (count == 1) {
                // Emulate the producer's armed notification while its full cache is consumed.
                parked.set(true);
              } else if (count == 2) {
                throw new IdException(IdException.Reason.COORDINATION, "Post-claim rejection");
              }
            };
        assertEquals(
            IdException.Reason.COORDINATION,
            assertThrows(IdException.class, generator::getUniqueId).getReason());
        assertFalse(parked.get(), "A burned claim must still notify the armed producer");
        lease.onCheck = count -> {};
        assertEquals(1, generator.snapshot().getBuffered());
        assertEquals(0, generator.snapshot().getGenerated());
      } finally {
        lease.onCheck = count -> {};
        paused.close();
      }
    }
  }

  @Test
  void claimAfterWaitingNotifiesBeforeItsDeadlineRejection() throws Exception {
    ObservedLease lease = new ObservedLease();
    try (PausedProducerTime paused = new PausedProducerTime(new ControlledTime());
        BufferedIdGenerator generator =
            new BufferedIdGenerator(
                GeneratorOptions.builder().capacity(2).waitTimeout(Duration.ofMillis(100)).build(),
                lease,
                paused)) {
      try {
        paused.awaitPaused();
        generator.getUniqueId();
        generator.getUniqueId();
        RingBuffer ring = field(generator, "ring", RingBuffer.class);
        AtomicBoolean parked = field(generator, "producerParked", AtomicBoolean.class);
        parked.set(false);
        lease.checks.set(0);
        lease.onCheck =
            count -> {
              if (count == 3) {
                // The real producer is held outside the ring. Publish at the registered recheck,
                // after the waiting caller's earlier refill request but before its claim.
                assertEquals(2, ring.offerRange(123, 2));
                parked.set(true);
              } else if (count == 4) {
                long start = System.nanoTime();
                while (System.nanoTime() - start < 150_000_000) {
                  LockSupport.parkNanos(1_000_000);
                }
              }
            };
        IdException failure = assertThrows(IdException.class, generator::getUniqueId);
        assertEquals(IdException.Reason.TIMEOUT, failure.getReason());
        assertFalse(parked.get(), "An expired claim must not suppress refill notification");
        lease.onCheck = count -> {};
        assertEquals(1, generator.snapshot().getDeadlineTimeouts());
        assertEquals(2, generator.snapshot().getGenerated());
        assertEquals(1, generator.snapshot().getBuffered());
      } finally {
        lease.onCheck = count -> {};
        paused.close();
      }
    }
  }

  private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(owner));
  }

  /** A local lease with test-controlled admission checkpoints and no external resources. */
  private static final class ObservedLease implements WorkerLease {
    private final AtomicInteger checks = new AtomicInteger();
    private volatile IntConsumer onCheck = count -> {};
    private volatile boolean closed;

    public long rawId() {
      return 7;
    }

    public long workerId() {
      return 7;
    }

    public State state() {
      if (closed) {
        return State.CLOSED;
      }
      onCheck.accept(checks.incrementAndGet());
      return State.ACTIVE;
    }

    public void close() {
      closed = true;
    }
  }
}
