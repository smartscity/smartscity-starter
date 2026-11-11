package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.observability.autoconfigure.ObservabilityProperties;
import com.smartscity.observability.http.ExchangeObservation;
import com.smartscity.observability.http.ObservationRuntime;
import com.smartscity.observability.logging.TextSanitizer;
import com.smartscity.observability.metrics.StatsdUdpSender;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
  void concurrentProducersAndClosersReleaseQueueAndWorker() throws Exception {
    try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      StatsdUdpSender sender =
          new StatsdUdpSender(
              "127.0.0.1", receiver.getLocalPort(), 64, 1400, Duration.ofSeconds(2));
      ExecutorService workers = Executors.newFixedThreadPool(6);
      CountDownLatch started = new CountDownLatch(1);
      List<Future<?>> tasks = new ArrayList<>();
      try {
        for (int p = 0; p < 4; p++) {
          tasks.add(
              workers.submit(
                  () -> {
                    started.await();
                    for (int i = 0; i < 2000; i++) {
                      sender.accept("test:1|c");
                    }
                    return null;
                  }));
        }
        for (int p = 0; p < 2; p++) {
          tasks.add(
              workers.submit(
                  () -> {
                    started.await();
                    sender.close();
                    assertFalse(sender.isAlive());
                    return null;
                  }));
        }
        started.countDown();
        for (Future<?> task : tasks) {
          task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, sender.queuedLines());
        long dropped = sender.droppedLines();
        sender.accept("after:1|c");
        assertEquals(dropped + 1, sender.droppedLines());
      } finally {
        sender.close();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void subMillisecondAndInterruptedCloseHaveNoUnboundedJoin() throws Exception {
    try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      for (Duration duration : new Duration[] {Duration.ofNanos(1), Duration.ofSeconds(1)}) {
        StatsdUdpSender sender =
            new StatsdUdpSender("127.0.0.1", receiver.getLocalPort(), 4, 1400, duration);
        try {
          Thread.currentThread().interrupt();
          sender.close();
          assertTrue(Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
          sender.close();
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sender.isAlive() && System.nanoTime() < until) {
          Thread.sleep(1);
        }
        assertFalse(sender.isAlive());
        assertEquals(0, sender.queuedLines());
      }
    }
  }

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
