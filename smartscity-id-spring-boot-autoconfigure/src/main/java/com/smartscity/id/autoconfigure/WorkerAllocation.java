package com.smartscity.id.autoconfigure;

import com.smartscity.id.autoconfigure.jdbc.JdbcWorkerRepository;
import com.smartscity.id.autoconfigure.zk.ZookeeperWorkerOccupancy;
import com.smartscity.id.spi.WorkerLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns occupancy and the best-effort offline callback. No periodic database work. */
final class WorkerAllocation implements WorkerLease {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerAllocation.class);
    private final long raw, worker, shutdownMillis;
    private final JdbcWorkerRepository repository;
    private final ZookeeperWorkerOccupancy occupancy;
    private final AtomicBoolean closed = new AtomicBoolean();
    WorkerAllocation(long raw, long worker, JdbcWorkerRepository repository, ZookeeperWorkerOccupancy occupancy,
                     long shutdownMillis) {
        this.raw = raw; this.worker = worker; this.repository = repository; this.occupancy = occupancy;
        this.shutdownMillis = shutdownMillis;
    }
    public long rawId() { return raw; }
    public long workerId() { return worker; }
    public State state() { return closed.get() ? State.CLOSED : occupancy == null ? State.ACTIVE : occupancy.state(); }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // One budget covers both external resources. Never cancel a task before it has run cleanup.
        boolean interrupted = Thread.interrupted();
        CompletableFuture<Void> released = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                try { if (occupancy != null) occupancy.close(); }
                finally { repository.offline(raw); }
                released.complete(null);
            } catch (Throwable e) { released.completeExceptionally(e); }
        }, "smartscity-id-release-" + raw);
        thread.setDaemon(true);
        try {
            thread.start(); released.get(shutdownMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { interrupted = true; }
        catch (ExecutionException | TimeoutException e) { LOG.warn("ID worker release incomplete; rawId={}", raw); }
        finally {
            if (!released.isDone()) thread.interrupt();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
