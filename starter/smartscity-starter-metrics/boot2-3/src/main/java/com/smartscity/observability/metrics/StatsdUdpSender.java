package com.smartscity.observability.metrics;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One bounded line queue and socket writer; local acceptance never promises UDP delivery.
 *
 * @author jack
 */
public final class StatsdUdpSender implements Consumer<String>, AutoCloseable {
  private final ArrayBlockingQueue<byte[]> queue;
  private final DatagramChannel socket;
  private final Thread worker;
  private final int packetBytes;
  private static final byte[] WAKE = new byte[0];
  private final long shutdownNanos;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong sendErrors = new AtomicLong();
  private final AtomicLong packets = new AtomicLong();
  private final Object lifecycle = new Object();
  private volatile boolean open = true;
  private volatile long deadline;

  /**
   * Creates and owns the sender. Destination resolution is startup work.
   *
   * @param host explicit receiver host
   * @param port receiver port
   * @param capacity maximum queued complete lines
   * @param packetBytes UDP payload limit
   * @param shutdown maximum drain/join duration
   * @throws IOException if the local socket cannot be initialized
   * @throws IllegalArgumentException if limits or destination are invalid
   */
  public StatsdUdpSender(String host, int port, int capacity, int packetBytes, Duration shutdown)
      throws IOException {
    if (host == null
        || host.trim().isEmpty()
        || port < 1
        || port > 65535
        || capacity < 1
        || capacity > 1_048_576
        || packetBytes < 128
        || packetBytes > 65507
        || shutdown.isNegative()
        || shutdown.isZero()
        || shutdown.compareTo(Duration.ofSeconds(10)) > 0) {
      throw new IllegalArgumentException("Invalid UDP settings");
    }
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.packetBytes = packetBytes;
    shutdownNanos = shutdown.toNanos();
    InetSocketAddress address = new InetSocketAddress(host, port);
    if (address.isUnresolved()) {
      throw new IllegalArgumentException("Unresolved StatsD destination");
    }
    socket = DatagramChannel.open();
    try {
      socket.configureBlocking(false);
      socket.connect(address);
    } catch (IOException | RuntimeException e) {
      socket.close();
      throw e;
    }
    worker = new Thread(this::run, "smartscity-statsd-udp");
    worker.setDaemon(true);
    try {
      worker.start();
    } catch (RuntimeException | Error e) {
      try {
        socket.close();
      } catch (IOException close) {
        e.addSuppressed(close);
      }
      throw e;
    }
  }

  @Override
  public void accept(String line) {
    if (!open
        || line == null
        || line.length() > packetBytes
        || line.indexOf('\n') >= 0
        || line.indexOf('\r') >= 0) {
      dropped.incrementAndGet();
      return;
    }
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0 || bytes.length > packetBytes) {
      dropped.incrementAndGet();
      return;
    }
    synchronized (lifecycle) {
      if (!open || !queue.offer(bytes)) {
        dropped.incrementAndGet();
      }
    }
  }

  private void run() {
    try {
      ByteBuffer packet = ByteBuffer.allocate(packetBytes);
      while (open || (!queue.isEmpty() && deadline - System.nanoTime() > 0)) {
        byte[] first;
        try {
          first = queue.take();
        } catch (InterruptedException e) {
          continue;
        }
        if (first == WAKE) {
          continue;
        }
        packet.clear();
        packet.put(first);
        int batch = 1;
        while (batch < 128) {
          byte[] next = queue.peek();
          if (next == null || next == WAKE || next.length + 1 > packet.remaining()) {
            break;
          }
          next = queue.poll();
          if (next != null) {
            packet.put((byte) '\n').put(next);
            batch++;
          }
        }
        packet.flip();
        try {
          int n = socket.write(packet);
          if (n == 0) {
            dropped.addAndGet(batch);
            sendErrors.incrementAndGet();
          } else {
            packets.incrementAndGet();
          }
        } catch (IOException e) {
          dropped.addAndGet(batch);
          sendErrors.incrementAndGet();
        }
      }
    } finally {
      synchronized (lifecycle) {
        if (open) {
          deadline = System.nanoTime();
        }
        open = false;
        byte[] remaining;
        while ((remaining = queue.poll()) != null) {
          if (remaining != WAKE) {
            dropped.incrementAndGet();
          }
        }
      }
      try {
        closeSocket();
      } finally {
        stopped.countDown();
      }
    }
  }

  private void closeSocket() {
    try {
      socket.close();
    } catch (IOException e) {
      sendErrors.incrementAndGet();
    }
  }

  public long droppedLines() {
    return dropped.get();
  }

  public long sendErrors() {
    return sendErrors.get();
  }

  public long sentPackets() {
    return packets.get();
  }

  public int queuedLines() {
    return queue.size();
  }

  public boolean isAlive() {
    return worker.isAlive();
  }

  /**
   * Stops acceptance and shares one bounded drain deadline across concurrent close callers. An
   * interrupted caller preserves its flag and forces socket cleanup. Scheduler delays can outlive
   * the deadline; no caller performs an unbounded join.
   */
  @Override
  public void close() {
    synchronized (lifecycle) {
      if (open) {
        deadline = System.nanoTime() + shutdownNanos;
        open = false;
        // A full queue already gives the consumer work; an empty queue needs this wake-up.
        queue.offer(WAKE);
      }
    }
    try {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0 || !stopped.await(remaining, TimeUnit.NANOSECONDS)) {
        closeSocket();
      } else {
        remaining = deadline - System.nanoTime();
        if (remaining > 0 && Thread.currentThread() != worker) {
          worker.join(remaining / 1_000_000, (int) (remaining % 1_000_000));
        }
      }
    } catch (InterruptedException e) {
      deadline = System.nanoTime();
      closeSocket();
      Thread.currentThread().interrupt();
    }
  }
}
