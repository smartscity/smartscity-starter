package com.smartscity.id.autoconfigure.zk;

import com.smartscity.id.api.IdException;
import com.smartscity.id.spi.WorkerLease;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One session per SDK. All hot-path queries read local state; no network calls there. */
public final class ZookeeperWorkerOccupancy implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ZookeeperWorkerOccupancy.class);
    private final ZooKeeper client;
    private final String parent;
    private final long timeoutMillis;
    private final List<ACL> acl;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile WorkerLease.State state = WorkerLease.State.SUSPENDED;
    private volatile String path;
    public ZookeeperWorkerOccupancy(String connect, String parent, int sessionMillis, long timeoutMillis,
                                     String authScheme, String auth) throws Exception {
        if (connect == null || connect.trim().isEmpty() || !parent.matches("(/[a-zA-Z0-9_-]+)+")
                || sessionMillis < 1000 || sessionMillis > 300000) throw new IllegalArgumentException("Invalid ZK configuration");
        this.parent = parent; this.timeoutMillis = timeoutMillis;
        if ((authScheme == null) != (auth == null)) throw new IllegalArgumentException("ZK auth requires scheme and data");
        acl = auth == null ? ZooDefs.Ids.OPEN_ACL_UNSAFE : ZooDefs.Ids.CREATOR_ALL_ACL;
        client = new ZooKeeper(connect, sessionMillis, this::onEvent);
        try {
            if (auth != null) client.addAuthInfo(authScheme, auth.getBytes(StandardCharsets.UTF_8));
            awaitConnected();
            String current = "";
            for (String part : parent.substring(1).split("/")) {
                current += "/" + part;
                int rc = create(current, new byte[0], CreateMode.PERSISTENT);
                if (rc != KeeperException.Code.OK.intValue() && rc != KeeperException.Code.NODEEXISTS.intValue()) fail(rc);
            }
        } catch (Exception | Error e) {
            try { close(); } catch (RuntimeException | Error cleanup) { if (cleanup != e) e.addSuppressed(cleanup); }
            throw e;
        }
    }
    private synchronized void onEvent(WatchedEvent event) {
        if (closed.get()) return;
        if (event.getType() == Watcher.Event.EventType.None) {
            connectionGeneration.incrementAndGet();
            switch (event.getState()) {
                case Expired: case AuthFailed: state = WorkerLease.State.EXPIRED; return;
                case SyncConnected:
                    if (state == WorkerLease.State.EXPIRED) return;
                    if (path != null) verifyOwner();
                    return;
                default:
                    if (state != WorkerLease.State.EXPIRED) state = WorkerLease.State.SUSPENDED;
                    return;
            }
        }
        if (path != null && path.equals(event.getPath())) {
            if (event.getType() == Watcher.Event.EventType.NodeDeleted) state = WorkerLease.State.EXPIRED;
            else verifyOwner();
        }
    }
    private synchronized void verifyOwner() {
        if (closed.get() || state == WorkerLease.State.EXPIRED) return;
        long generation = connectionGeneration.get();
        state = WorkerLease.State.SUSPENDED;
        client.exists(path, true, (rc, p, context, stat) -> {
            synchronized (ZookeeperWorkerOccupancy.this) {
                if (closed.get() || generation != connectionGeneration.get() || state == WorkerLease.State.EXPIRED) return;
                if (rc == KeeperException.Code.OK.intValue() && stat != null
                        && stat.getEphemeralOwner() == client.getSessionId()) state = WorkerLease.State.ACTIVE;
                else if (rc == KeeperException.Code.NONODE.intValue()
                        || rc == KeeperException.Code.OK.intValue()) state = WorkerLease.State.EXPIRED;
            }
        }, null);
    }
    private void awaitConnected() throws InterruptedException, TimeoutException {
        long start = System.nanoTime();
        while (!client.getState().isConnected()) {
            if (state == WorkerLease.State.EXPIRED) throw new IdException(IdException.Reason.COORDINATION, "ZK session invalid");
            if ((System.nanoTime() - start) / 1_000_000 >= timeoutMillis) throw new TimeoutException("ZK connect timeout");
            Thread.sleep(5);
        }
    }
    private int create(String target, byte[] data, CreateMode mode) throws Exception {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        client.create(target, data, acl, mode, (rc, p, context, name) -> result.complete(rc), null);
        return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
    }
    public boolean tryOccupy(long worker, long raw) throws Exception {
        if (path != null) throw new IllegalStateException("Already occupied");
        String candidate = parent + "/" + worker;
        int rc = create(candidate, Long.toString(raw).getBytes(StandardCharsets.UTF_8), CreateMode.EPHEMERAL);
        if (rc == KeeperException.Code.NODEEXISTS.intValue()) return false;
        if (rc != KeeperException.Code.OK.intValue()) fail(rc);
        path = candidate;
        verifyOwner();
        long start = System.nanoTime();
        while (state == WorkerLease.State.SUSPENDED) {
            if ((System.nanoTime() - start) / 1_000_000 >= timeoutMillis) throw new TimeoutException("ZK owner verification timeout");
            Thread.sleep(2);
        }
        if (state != WorkerLease.State.ACTIVE) throw new IdException(IdException.Reason.COORDINATION, "ZK ownership lost");
        return true;
    }
    private static void fail(int rc) throws KeeperException { throw KeeperException.create(KeeperException.Code.get(rc)); }
    public WorkerLease.State state() { return closed.get() ? WorkerLease.State.CLOSED : state; }
    @Override public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            connectionGeneration.incrementAndGet(); state = WorkerLease.State.CLOSED;
        }
        // ZooKeeper.close(int) bounds only thread joins, not the preceding closeSession request.
        boolean interrupted = Thread.interrupted();
        int budget = (int) Math.max(1, Math.min(timeoutMillis, 2000));
        CompletableFuture<Void> released = new CompletableFuture<>();
        Thread closer = new Thread(() -> {
            try { client.close(budget); released.complete(null); }
            catch (Throwable e) { released.completeExceptionally(e); }
        }, "smartscity-id-zk-close");
        closer.setDaemon(true);
        try { closer.start(); released.get(budget, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { interrupted = true; }
        catch (ExecutionException | TimeoutException e) { LOG.warn("ID ZooKeeper close incomplete; local issuance is stopped"); }
        finally {
            if (!released.isDone()) closer.interrupt();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
