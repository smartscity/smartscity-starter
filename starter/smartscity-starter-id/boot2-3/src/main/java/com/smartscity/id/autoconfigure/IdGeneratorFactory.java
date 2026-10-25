package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import com.smartscity.id.autoconfigure.jdbc.DatabaseDialect;
import com.smartscity.id.autoconfigure.jdbc.JdbcWorkerRepository;
import com.smartscity.id.autoconfigure.jdbc.JdbcWorkerRepository.Registration;
import com.smartscity.id.autoconfigure.jdbc.WorkerInstance;
import com.smartscity.id.autoconfigure.zk.ZookeeperWorkerOccupancy;
import com.smartscity.id.core.CachedUniqueIdGenerator;
import com.smartscity.id.core.GeneratorOptions;
import com.smartscity.id.core.MonotonicClock;
import com.smartscity.id.spi.TimeSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root: configuration, external allocation, then local generator.
 *
 * @author jack
 */
public final class IdGeneratorFactory {
  private static final Logger LOG = LoggerFactory.getLogger(IdGeneratorFactory.class);

  /**
   * Registers one incarnation and transfers its lifecycle ownership to a ready generator.
   *
   * <p>Configuration must not be mutated concurrently. A supplied DataSource is application-owned;
   * only its borrowed connections are closed. Failed or uncertain registrations are not retried.
   *
   * @param properties configuration validated before external allocation
   * @param supplied explicitly selected application DataSource, or null to use the configured URL
   * @return the ready generator, which the caller must close
   * @throws IdException on invalid configuration, registration failure, timeout or interruption
   */
  public IdGenerator create(IdProperties properties, DataSource supplied) {
    final GeneratorOptions options;
    final WorkerInstance instance;
    try {
      options = properties.options();
      IdProperties.Instance identity = properties.getInstance();
      instance =
          new WorkerInstance(
              identity.getId(),
              identity.getApplicationName(),
              identity.getHostIp(),
              identity.getHostPort(),
              identity.getHostname());
      if (supplied != null && properties.getDatabase().getUrl() != null) {
        throw new IllegalArgumentException(
            "Configure either a database URL or an application DataSource, not both");
      }
      if (properties.getDatabase().isUseApplicationDataSource() && supplied == null) {
        throw new IllegalArgumentException(
            "database.use-application-data-source requires one unique or primary DataSource");
      }
      DatabaseDialect.from(properties.getDatabase().getType());
      if (properties.getProfile().equals("javascript")) {
        if (!org.springframework.util.ClassUtils.isPresent(
            "org.apache.zookeeper.ZooKeeper", getClass().getClassLoader())) {
          throw new IllegalArgumentException(
              "JavaScript profile requires the ZooKeeper client dependency");
        }
        if (properties.getZookeeper().getConnectString() == null) {
          throw new IllegalArgumentException(
              "JavaScript profile requires zookeeper.connect-string");
        }
      }
    } catch (RuntimeException e) {
      throw new IdException(IdException.Reason.CONFIGURATION, "Invalid ID configuration", e);
    }
    // Bounds startup even when a supplied driver ignores JDBC query timeouts.
    CompletableFuture<IdGenerator> result = new CompletableFuture<>();
    Thread starter =
        new Thread(
            () -> {
              try {
                IdGenerator generator =
                    allocate(properties, options, instance, supplied, result::isCancelled);
                if (!result.complete(generator)) {
                  generator.close();
                }
              } catch (Throwable e) {
                result.completeExceptionally(e);
              }
            },
            "smartscity-id-startup");
    starter.setDaemon(true);
    starter.start();
    try {
      return result.get(properties.getStartupTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      cancelStartup(result, starter);
      Thread.currentThread().interrupt();
      throw new IdException(IdException.Reason.INTERRUPTED, "ID startup interrupted", e);
    } catch (Exception e) {
      cancelStartup(result, starter);
      Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
      if (cause instanceof IdException) {
        throw (IdException) cause;
      }
      throw new IdException(IdException.Reason.STARTUP, "ID startup failed", cause);
    }
  }

  private static void cancelStartup(CompletableFuture<IdGenerator> result, Thread starter) {
    if (!result.cancel(false)) {
      // Completion may win just after get(timeout) failed; the caller must not lose ownership.
      result.thenAccept(
          generator -> {
            Thread cleanup = new Thread(generator::close, "smartscity-id-canceled-startup");
            cleanup.setDaemon(true);
            cleanup.start();
          });
    }
    starter.interrupt();
  }

  private static void checkCanceled(BooleanSupplier canceled) throws InterruptedException {
    if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("ID startup canceled");
    }
  }

  private IdGenerator allocate(
      IdProperties p,
      GeneratorOptions options,
      WorkerInstance instance,
      DataSource supplied,
      BooleanSupplier canceled)
      throws Exception {

    ZookeeperWorkerOccupancy occupancy = null;
    WorkerAllocation allocation = null;
    CachedUniqueIdGenerator generator = null;
    try {
      checkCanceled(canceled);
      DataSource ds = supplied;
      if (p.getDatabase().getUrl() != null) {
        ds = new DriverDataSource(p.getDatabase(), p.getStartupTimeout().toMillis());
      }
      if (ds == null) {
        throw new IdException(
            IdException.Reason.CONFIGURATION, "An ID database URL or DataSource is required");
      }
      JdbcWorkerRepository repository =
          new JdbcWorkerRepository(
              ds,
              DatabaseDialect.from(p.getDatabase().getType()),
              (int) Math.max(1, p.getStartupTimeout().getSeconds()));
      checkCanceled(canceled);
      if (p.getProfile().equals("javascript")) {
        IdProperties.Zookeeper zk = p.getZookeeper();
        occupancy =
            new ZookeeperWorkerOccupancy(
                zk.getConnectString(),
                zk.getRoot() + "/" + p.getGroup() + "/workers",
                Math.toIntExact(zk.getSessionTimeout().toMillis()),
                p.getStartupTimeout().toMillis(),
                zk.getAuthScheme(),
                zk.getAuth());
      }
      {
        checkCanceled(canceled);
        Registration registration = repository.register(p.getGroup(), options.bits, instance);
        long raw = registration.getId();
        long worker = registration.getWorkerId();
        // Own the committed row before any subsequent network operation can fail.
        allocation =
            new WorkerAllocation(
                registration, repository, occupancy, p.getShutdownTimeout().toMillis());
        checkCanceled(canceled);
        if (occupancy != null && !occupancy.tryOccupy(worker, raw)) {
          throw new IdException(
              IdException.Reason.COORDINATION,
              "Never-reused worker unexpectedly occupied: group="
                  + p.getGroup()
                  + ", workerId="
                  + worker);
        }
        MonotonicClock clock = new MonotonicClock(TimeSource.SYSTEM, options.clockToleranceMillis);
        repository.checkTime(clock, options.clockToleranceMillis);
        checkCanceled(canceled);
        generator = CachedUniqueIdGenerator.usingClock(options, allocation, clock);
        allocation.started();
        checkCanceled(canceled);
        LOG.info(
            "ID ready: group={}, layout={}, epoch={}, rawId={}, worker={}",
            p.getGroup(),
            options.bits.signature(),
            options.bits.getLayout().getEpoch(),
            raw,
            worker);
        return new ManagedIdGenerator(generator);
      }
    } catch (Exception | Error e) {
      try {
        if (generator != null) {
          generator.close();
        } else if (allocation != null) {
          allocation.close();
        } else if (occupancy != null) {
          occupancy.close();
        }
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != e) {
          e.addSuppressed(cleanup);
        }
      }
      throw e;
    }
  }
}
