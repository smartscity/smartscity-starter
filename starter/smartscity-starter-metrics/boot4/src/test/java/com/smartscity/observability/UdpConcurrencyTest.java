package com.smartscity.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

/**
 * Contends real producers, closers and exchange completion without timing throughput.
 *
 * @author jack
 */
class UdpConcurrencyTest {
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
}
