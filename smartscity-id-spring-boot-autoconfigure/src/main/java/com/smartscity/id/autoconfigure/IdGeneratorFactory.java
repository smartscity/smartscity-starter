package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import com.smartscity.id.core.*;
import com.smartscity.id.spi.TimeSource;
import com.smartscity.id.autoconfigure.jdbc.*;
import com.smartscity.id.autoconfigure.zk.ZookeeperWorkerOccupancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Composition root: configuration, external allocation, then local generator. */
public final class IdGeneratorFactory {
    private static final Logger LOG = LoggerFactory.getLogger(IdGeneratorFactory.class);
    public IdGenerator create(IdProperties properties, DataSource supplied) {
        final GeneratorOptions options;
        try {
            options = properties.options();
            DatabaseDialect.from(properties.getDatabase().getType());
            if (properties.getProfile().equals("javascript")) {
                if (!org.springframework.util.ClassUtils.isPresent("org.apache.zookeeper.ZooKeeper", getClass().getClassLoader())) {
                    throw new IllegalArgumentException("JavaScript profile requires the ZooKeeper client dependency");
                }
                if (properties.getZookeeper().getConnectString() == null) {
                    throw new IllegalArgumentException("JavaScript profile requires zookeeper.connect-string");
                }
            }
        } catch (RuntimeException e) {
            throw new IdException(IdException.Reason.CONFIGURATION, "Invalid ID configuration", e);
        }
        // Bounds startup even when a supplied driver ignores JDBC query timeouts.
        CompletableFuture<IdGenerator> result = new CompletableFuture<>();
        Thread starter = new Thread(() -> {
            try {
                IdGenerator generator = allocate(properties, options, supplied, result::isCancelled);
                if (!result.complete(generator)) generator.close();
            } catch (Throwable e) { result.completeExceptionally(e); }
        }, "smartscity-id-startup");
        starter.setDaemon(true); starter.start();
        try { return result.get(properties.getStartupTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            cancelStartup(result, starter); Thread.currentThread().interrupt();
            throw new IdException(IdException.Reason.INTERRUPTED, "ID startup interrupted", e);
        } catch (Exception e) {
            cancelStartup(result, starter);
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            if (cause instanceof IdException) throw (IdException) cause;
            throw new IdException(IdException.Reason.STARTUP, "ID startup failed", cause);
        }
    }
    private static void cancelStartup(CompletableFuture<IdGenerator> result, Thread starter) {
        if (!result.cancel(false)) {
            // Completion may win just after get(timeout) failed; the caller must not lose ownership.
            result.thenAccept(generator -> {
                Thread cleanup = new Thread(generator::close, "smartscity-id-canceled-startup");
                cleanup.setDaemon(true); cleanup.start();
            });
        }
        starter.interrupt();
    }
    private static void checkCanceled(BooleanSupplier canceled) throws InterruptedException {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("ID startup canceled");
    }
    private IdGenerator allocate(IdProperties p, GeneratorOptions options, DataSource supplied, BooleanSupplier canceled) throws Exception {

        ZookeeperWorkerOccupancy occupancy = null;
        WorkerAllocation allocation = null;
        CachedUniqueIdGenerator generator = null;
        try {
            checkCanceled(canceled);
            DataSource ds = supplied;
            if (p.getDatabase().getUrl() != null) {
                ds = new DriverDataSource(p.getDatabase(), p.getStartupTimeout().toMillis());
            }
            if (ds == null) throw new IdException(IdException.Reason.CONFIGURATION, "An ID database URL or DataSource is required");
            JdbcWorkerRepository repository = new JdbcWorkerRepository(ds, DatabaseDialect.from(p.getDatabase().getType()),
                    (int) Math.max(1, p.getStartupTimeout().getSeconds()));
            repository.validateGroup(p.getGroup(), options.bits);
            checkCanceled(canceled);
            if (p.getProfile().equals("javascript")) {
                IdProperties.Zookeeper zk = p.getZookeeper();
                occupancy = new ZookeeperWorkerOccupancy(zk.getConnectString(), zk.getRoot() + "/" + p.getGroup() + "/workers",
                        Math.toIntExact(zk.getSessionTimeout().toMillis()), p.getStartupTimeout().toMillis(), zk.getAuthScheme(), zk.getAuth());
            }
            for (int attempt = 0; attempt < p.getWorkerAttempts(); attempt++) {
                checkCanceled(canceled);
                long raw = repository.insert(p.getGroup());
                long worker = raw % options.bits.getWorkerSlots();
                // Own the committed row before any subsequent network operation can fail.
                allocation = new WorkerAllocation(raw, worker, repository, occupancy, p.getShutdownTimeout().toMillis());
                checkCanceled(canceled);
                if (occupancy != null && !occupancy.tryOccupy(worker, raw)) {
                    repository.offline(raw); allocation = null; continue;
                }
                MonotonicClock clock = new MonotonicClock(TimeSource.SYSTEM, options.clockToleranceMillis);
                repository.checkTime(clock, options.clockToleranceMillis);
                checkCanceled(canceled);
                generator = CachedUniqueIdGenerator.usingClock(options, allocation, clock);
                LOG.info("ID ready: group={}, layout={}, epoch={}, rawId={}, worker={}", p.getGroup(),
                        options.bits.signature(), BitsAllocator.EPOCH, raw, worker);
                return new ManagedIdGenerator(generator);
            }
            throw new IdException(IdException.Reason.STARTUP, "Worker allocation attempts exhausted");
        } catch (Exception | Error e) {
            try {
                if (generator != null) generator.close();
                else if (allocation != null) allocation.close();
                else if (occupancy != null) occupancy.close();
            } catch (RuntimeException | Error cleanup) { if (cleanup != e) e.addSuppressed(cleanup); }
            throw e;
        }
    }
}
