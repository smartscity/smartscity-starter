package com.smartscity.observability.benchmark;

import com.smartscity.observability.metrics.StatsdUdpSender;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded loopback load probe with one producer and one receiver per trial. Reports local loss and
 * sampled admission cost separately from receiver delivery; it does not measure network latency.
 *
 * @author jack
 */
public final class UdpLoadProbe {
  private static final String LINE = "benchmark.requests:1|c|#service:synthetic,route:echo";

  private UdpLoadProbe() {}

  /**
   * Executes six isolated measurement trials and writes each result immediately.
   *
   * @param args output directory and optional measurement seconds (default eight)
   * @throws Exception if resources fail, cleanup fails or arguments are invalid
   */
  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 8;
    if (seconds < 1 || seconds > 8) {
      throw new IllegalArgumentException(
          "Measurement duration must be between one and eight seconds");
    }
    for (int rate : new int[] {10000, 50000, 100000}) {
      for (int trial = 1; trial <= 2; trial++) {
        run(rate, 2);
        String result = run(rate, seconds);
        Files.writeString(output.resolve("udp-" + rate + "-" + trial + ".json"), result);
        System.out.println(result);
      }
    }
  }

  private static String run(int rate, int seconds) throws Exception {
    AtomicLong received = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      receiver.setReceiveBufferSize(4 * 1024 * 1024);
      receiver.setSoTimeout(100);
      int receiveBuffer = receiver.getReceiveBufferSize();
      Thread reader =
          new Thread(() -> receive(receiver, received, failure), "benchmark-udp-receiver");
      reader.setDaemon(true);
      reader.start();
      try (StatsdUdpSender sender =
          new StatsdUdpSender(
              InetAddress.getLoopbackAddress().getHostAddress(),
              receiver.getLocalPort(),
              8192,
              1400,
              Duration.ofSeconds(2))) {
        long[] samples = new long[8192];
        int sampleCount = 0;
        long attempts = 0;
        long start = System.nanoTime();
        long end = start + Duration.ofSeconds(seconds).toNanos();
        long tick = start;
        int batch = rate / 1000;
        while (System.nanoTime() < end) {
          long now = System.nanoTime();
          if (tick > now) {
            LockSupport.parkNanos(tick - now);
            continue;
          }
          // Do not manufacture a catch-up burst after a scheduler stall.
          tick = Math.max(tick + 1_000_000, now);
          for (int i = 0; i < batch; i++) {
            if ((attempts & 1023) == 0 && sampleCount < samples.length) {
              long before = System.nanoTime();
              sender.accept(LINE);
              samples[sampleCount++] = System.nanoTime() - before;
            } else {
              sender.accept(LINE);
            }
            attempts++;
          }
        }
        long elapsed = System.nanoTime() - start;
        long drainStart = System.nanoTime();
        sender.close();
        long drainNanos = System.nanoTime() - drainStart;
        // Allow already-written loopback packets to arrive; this is outside offered-load timing.
        long settleEnd = System.nanoTime() + 500_000_000L;
        while (received.get() < attempts - sender.droppedLines() && System.nanoTime() < settleEnd) {
          LockSupport.parkNanos(1_000_000);
        }
        receiver.close();
        reader.join(1000);
        if (reader.isAlive()
            || sender.isAlive()
            || sender.queuedLines() != 0
            || failure.get() != null) {
          throw new IllegalStateException("Probe resource cleanup/receiver failure", failure.get());
        }
        Arrays.sort(samples, 0, sampleCount);
        double offered = attempts * 1e9 / elapsed;
        long written = attempts - sender.droppedLines();
        long loss = written - received.get();
        if (loss < 0) {
          throw new IllegalStateException("Receiver count exceeds locally written lines");
        }
        return String.format(
            Locale.ROOT,
            "{\"target_rate\":%d,\"offered_rate\":%.3f,\"duration_ns\":%d,\"attempts\":%d,\"dropped\":%d,\"locally_written\":%d,\"received\":%d,\"delivery_gap\":%d,\"packets\":%d,\"send_errors\":%d,\"offer_samples\":%d,\"offer_p50_ns\":%d,\"offer_p99_ns\":%d,\"drain_ns\":%d,\"receive_buffer_bytes\":%d,\"cleanup\":\"PASS\",\"load_status\":\"%s\"}%n",
            rate,
            offered,
            elapsed,
            attempts,
            sender.droppedLines(),
            written,
            received.get(),
            loss,
            sender.sentPackets(),
            sender.sendErrors(),
            sampleCount,
            samples[(sampleCount - 1) / 2],
            samples[(int) Math.ceil(sampleCount * .99) - 1],
            drainNanos,
            receiveBuffer,
            offered >= rate * .95 ? "ACHIEVED" : "UNDERDRIVEN");
      } finally {
        receiver.close();
        reader.join(1000);
      }
    }
  }

  private static void receive(
      DatagramSocket receiver, AtomicLong received, AtomicReference<Throwable> failure) {
    byte[] buffer = new byte[65507];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    try {
      while (!receiver.isClosed()) {
        try {
          packet.setLength(buffer.length);
          receiver.receive(packet);
          long lines = 1;
          for (int i = 0; i < packet.getLength(); i++) {
            if (buffer[i] == '\n') {
              lines++;
            }
          }
          received.addAndGet(lines);
        } catch (SocketTimeoutException ignored) {
          // Recheck shutdown without an unbounded receive.
        }
      }
    } catch (SocketException e) {
      if (!receiver.isClosed()) {
        failure.set(e);
      }
    } catch (IOException | RuntimeException e) {
      failure.set(e);
    }
  }
}
