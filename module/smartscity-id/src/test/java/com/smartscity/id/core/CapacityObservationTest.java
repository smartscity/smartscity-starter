package com.smartscity.id.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdSnapshot;
import com.smartscity.id.api.RegistryCapacity;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Verifies logical capacity and cold-path diagnostics without consuming IDs during observation.
 *
 * @author jack
 */
class CapacityObservationTest {
  @Test
  void futureTimeAndRemainingCapacityFollowPublishedLogicalSeconds() throws Exception {
    Instant epoch =
        Instant.ofEpochMilli(System.currentTimeMillis())
            .plusSeconds(3600)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    BitsAllocator bits = new BitsAllocator(new IdLayout(epoch, 10, 3, 1));
    ControlledTime clock = new ControlledTime();
    clock.set(epoch.toEpochMilli(), 0);
    try (PausedProducerTime paused = new PausedProducerTime(clock);
        CachedUniqueIdGenerator generator =
            new CachedUniqueIdGenerator(
                GeneratorOptions.builder().bits(bits).capacity(8).build(),
                new CoreTest.Lease(),
                paused)) {
      paused.awaitPaused();
      IdSnapshot snapshot = generator.snapshot();
      assertEquals(epoch.plusSeconds(3).toEpochMilli(), snapshot.getLogicalTimeMillis());
      assertTrue(snapshot.getFutureTimeMillis() > 3_500_000);
      assertEquals(bits.getMaxTime() - 3, snapshot.getRemainingTimeSeconds());
      assertEquals(8, snapshot.getWorkerSlots());
      assertEquals(RegistryCapacity.Status.UNKNOWN, snapshot.getRegistryCapacity().getStatus());
      assertEquals(-1, snapshot.getRegistryCapacity().getRemaining());
      assertEquals(0, generator.snapshot().getGenerated());
      assertEquals(8, generator.snapshot().getBuffered());
    }
  }

  @Test
  void emptyTimeoutHasAStageAndDoesNotDiscardTheOriginalDeadline() throws Exception {
    try (PausedProducerTime paused = new PausedProducerTime(new ControlledTime());
        CachedUniqueIdGenerator generator =
            new CachedUniqueIdGenerator(
                GeneratorOptions.builder().capacity(2).waitTimeout(Duration.ofMillis(2)).build(),
                new CoreTest.Lease(),
                paused)) {
      paused.awaitPaused();
      generator.getUniqueId();
      generator.getUniqueId();
      assertEquals(
          IdException.Reason.TIMEOUT,
          assertThrows(IdException.class, generator::getUniqueId).getReason());
      IdSnapshot snapshot = generator.snapshot();
      assertEquals(1, snapshot.getEmptyTimeouts());
      assertEquals(0, snapshot.getContentionTimeouts());
      assertEquals(0, snapshot.getDeadlineTimeouts());
      assertTrue(snapshot.getEmptyPolls() > 0);
      assertEquals(1, snapshot.getWaits());
    }
  }
}
