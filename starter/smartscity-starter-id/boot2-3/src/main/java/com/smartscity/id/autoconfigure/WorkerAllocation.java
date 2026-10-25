package com.smartscity.id.autoconfigure;

import com.smartscity.id.autoconfigure.jdbc.JdbcWorkerRepository;
import com.smartscity.id.autoconfigure.zk.ZookeeperWorkerOccupancy;
import com.smartscity.id.spi.WorkerLease;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns optional occupancy and best-effort termination metadata; a closed worker is never reused.
 *
 * @author jack
 */
final class WorkerAllocation implements WorkerLease {
  private static final Logger LOG = LoggerFactory.getLogger(WorkerAllocation.class);
  private final JdbcWorkerRepository.Registration registration;
  private final long raw;
  private final long worker;
  private final long shutdownMillis;

  private final JdbcWorkerRepository repository;
  private final ZookeeperWorkerOccupancy occupancy;
  private volatile boolean started;
  private final AtomicBoolean closed = new AtomicBoolean();

  WorkerAllocation(
      JdbcWorkerRepository.Registration registration,
      JdbcWorkerRepository repository,
      ZookeeperWorkerOccupancy occupancy,
      long shutdownMillis) {
    this.registration = registration;
    this.raw = registration.getId();
    this.worker = registration.getWorkerId();
    this.repository = repository;
    this.occupancy = occupancy;
    this.shutdownMillis = shutdownMillis;
  }

  void started() throws java.sql.SQLException {
    repository.started(registration);
    started = true;
  }

  public long rawId() {
    return raw;
  }

  public long workerId() {
    return worker;
  }

  public State state() {
    return closed.get() ? State.CLOSED : occupancy == null ? State.ACTIVE : occupancy.state();
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    // One budget covers both external resources. Never cancel a task before it has run cleanup.
    boolean interrupted = Thread.interrupted();
    CompletableFuture<Void> released = new CompletableFuture<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                try {
                  if (occupancy != null) {
                    occupancy.close();
                  }
                } finally {
                  repository.stopped(registration, started);
                }
                released.complete(null);
              } catch (Throwable e) {
                released.completeExceptionally(e);
              }
            },
            "smartscity-id-release-" + raw);
    thread.setDaemon(true);
    try {
      thread.start();
      released.get(shutdownMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      interrupted = true;
    } catch (ExecutionException | TimeoutException e) {
      LOG.warn("ID worker release incomplete; rawId={}", raw);
    } finally {
      if (!released.isDone()) {
        thread.interrupt();
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
