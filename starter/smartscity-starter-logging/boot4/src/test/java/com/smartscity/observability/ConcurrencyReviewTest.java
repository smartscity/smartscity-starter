package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.observability.autoconfigure.ObservabilityProperties;
import com.smartscity.observability.http.ExchangeObservation;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.logging.TextSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Contends real producers, closers and exchange completion without timing throughput.
 *
 * @author jack
 */
class ConcurrencyReviewTest {
  @Test
  void exchangeCloseAndConcurrentCompletionReleaseOnce() throws Exception {
    ObservabilityProperties properties = new ObservabilityProperties();
    properties.getLogging().getBody().setEnabled(true);
    properties.getLogging().getBody().getPaths().add("/review");
    ObservationRuntime runtime =
        new ObservationRuntime(
            properties, "review", TextSanitizer.defaults(), (bytes, type) -> "safe", null, null);
    ExchangeObservation exchange = new ExchangeObservation(runtime, null, "/review", "local", true);
    AtomicInteger completed = new AtomicInteger();
    exchange.onCompletion(completed::incrementAndGet);
    exchange.request("/review", "application/json", null);
    exchange.response("/review", "application/json", null);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<?>> futures = new ArrayList<>();
      futures.add(pool.submit(() -> exchange.finish(200, true, true)));
      futures.add(pool.submit(exchange::close));
      futures.add(
          pool.submit(
              () -> {
                exchange.resetResponse();
                exchange.response("/review", "application/json", null);
              }));
      for (Future<?> f : futures) {
        f.get(3, TimeUnit.SECONDS);
      }
      assertTrue(exchange.isComplete());
      assertEquals(1, completed.get());
      assertEquals(0, runtime.budget().reservedBytes());
      exchange.resetResponse();
      assertNull(exchange.response("/review", "application/json", null));
    } finally {
      exchange.close();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
    }
  }
}
