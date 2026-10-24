package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import com.smartscity.id.autoconfigure.zk.ZookeeperWorkerOccupancy;
import com.smartscity.id.spi.WorkerLease;
import org.apache.zookeeper.*;
import org.apache.zookeeper.server.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "id.test.restart", matches = "true")
class ZookeeperRestartStabilityTest {
    @TempDir Path directory;
    ZooKeeperServer server;
    NIOServerCnxnFactory network;
    String address;
    Set<Long> initialClients;
    @BeforeEach void start() throws Exception {
        initialClients = clientThreads();
        System.setProperty("zookeeper.nio.numWorkerThreads", "2");
        System.setProperty("zookeeper.nio.numSelectorThreads", "1");
        server = new ZooKeeperServer(directory.resolve("snap").toFile(), directory.resolve("log").toFile(), 500);
        network = new NIOServerCnxnFactory();
        network.configure(new InetSocketAddress("127.0.0.1", 0), 20); network.startup(server);
        address = "127.0.0.1:" + network.getLocalPort();
    }
    @AfterEach void stop() throws Exception {
        try { if (network != null) network.shutdown(); }
        finally { if (server != null) server.shutdown(); }
        RestartDatabase.noSdkThreads();
        RestartDatabase.await(() -> initialClients.containsAll(clientThreads()), 5, "ZooKeeper client thread leaked");
    }
    private Set<Long> clientThreads() {
        Set<Long> ids = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && (thread.getName().contains("-SendThread(") || thread.getName().endsWith("-EventThread"))) ids.add(thread.getId());
        }
        return ids;
    }
    private IdProperties properties(RestartDatabase database, String group, String root) {
        IdProperties p = database.suppliedConfig(group); p.setProfile("javascript");
        p.getZookeeper().setConnectString(address); p.getZookeeper().setRoot(root); return p;
    }
    @Test @Timeout(90)
    void slotCanBeClaimedAndReleasedFiftyTimesWithoutLeavingSessions() throws Exception {
        try (ZooKeeper admin = new ZooKeeper(address, 10000, event -> { })) {
            for (int cycle = 0; cycle < 50; cycle++) {
                try (ZookeeperWorkerOccupancy owner = new ZookeeperWorkerOccupancy(address, "/reuse/workers", 10000, 3000, null, null)) {
                    assertTrue(owner.tryOccupy(7, 7L + cycle * 1024L));
                    assertEquals(WorkerLease.State.ACTIVE, owner.state());
                }
                assertTrue(admin.getChildren("/reuse/workers", false).isEmpty(), "Closed owner left an occupied slot");
            }
        }
        System.out.println("ZK_REUSE cycles=50 sameSlot=7 orphanNodes=0");
        // This checks occupancy lifecycle, not same-second ID uniqueness on a reused slot.
    }
    @Test @Timeout(90)
    void repeatedFailedAndSuccessfulSdkStartsReleaseRowsConnectionsAndNodes() throws Exception {
        RestartDatabase database = new RestartDatabase();
        String denied = "denied_" + System.nanoTime(), good = "good_" + System.nanoTime();
        Set<Long> ids = new HashSet<>();
        try (ZooKeeper admin = new ZooKeeper(address, 10000, event -> { })) {
            admin.create("/denied", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            admin.create("/denied/" + denied, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            String parent = "/denied/" + denied + "/workers";
            admin.create(parent, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            admin.setACL(parent, Collections.singletonList(new org.apache.zookeeper.data.ACL(
                    ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE)), -1);
            for (int cycle = 0; cycle < 25; cycle++) {
                IdException failure = assertThrows(IdException.class, () ->
                        new IdGeneratorFactory().create(properties(database, denied, "/denied"), database.tracked));
                assertInstanceOf(KeeperException.NoAuthException.class, failure.getCause());
                try (IdGenerator generator = new IdGeneratorFactory().create(properties(database, good, "/success"), database.tracked)) {
                    int connections = database.opened.get();
                    for (int id = 0; id < 16; id++) assertTrue(ids.add(generator.getUniqueId()));
                    assertEquals(connections, database.opened.get());
                }
                assertTrue(admin.getChildren("/success/" + good + "/workers", false).isEmpty());
                assertEquals(0, database.active.get()); RestartDatabase.noSdkThreads();
            }
            assertEquals(25, database.rows(denied, "OFFLINE")); assertEquals(0, database.rows(denied, "ONLINE"));
            assertEquals(25, database.rows(good, "OFFLINE")); assertEquals(0, database.rows(good, "ONLINE"));
        }
        assertEquals(400, ids.size()); assertEquals(0, database.active.get());
        System.out.println("JS_RESTART failedStarts=25 successfulStarts=25 ids=400 connections=0 orphanNodes=0");
    }
    @Test @Timeout(60)
    void fiveDisconnectsUnderTrafficRecoverButExpiredOwnerCannotResume() throws Exception {
        RestartDatabase database = new RestartDatabase(); String group = "flap_" + System.nanoTime();
        ExecutorService consumers = Executors.newSingleThreadExecutor();
        AtomicBoolean stop = new AtomicBoolean(); AtomicInteger generated = new AtomicInteger(), rejected = new AtomicInteger();
        Set<Long> ids = new HashSet<>();
        try (IdGenerator generator = new IdGeneratorFactory().create(properties(database, group, "/traffic"), database.tracked);
             ZooKeeper admin = new ZooKeeper(address, 10000, event -> { })) {
            String node = "/traffic/" + group + "/workers/" + generator.snapshot().getWorkerId();
            long session = admin.exists(node, false).getEphemeralOwner();
            int startupConnections = database.opened.get();
            Future<?> traffic = consumers.submit(() -> {
                while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        long id = generator.getUniqueId();
                        assertTrue(ids.add(id), "Duplicate during ZK flapping");
                        assertTrue(generated.incrementAndGet() < 200000, "Traffic budget exhausted");
                    } catch (IdException e) {
                        if (e.getReason() != IdException.Reason.COORDINATION && e.getReason() != IdException.Reason.TIMEOUT) throw e;
                        if (e.getReason() == IdException.Reason.COORDINATION) rejected.incrementAndGet();
                    }
                    LockSupport.parkNanos(1_000_000);
                }
            });
            try {
                for (int cycle = 0; cycle < 5; cycle++) {
                    int before = generated.get(), denied = rejected.get();
                    RestartDatabase.await(() -> generated.get() > before + 10 || traffic.isDone(), 3, "Traffic stopped");
                    if (traffic.isDone()) traffic.get();
                    network.closeAll(ServerCnxn.DisconnectReason.SERVER_SHUTDOWN);
                    RestartDatabase.await(() -> !generator.snapshot().isReady(), 3, "Disconnect was not observed");
                    RestartDatabase.await(() -> rejected.get() > denied || traffic.isDone(), 3, "Traffic did not encounter closed gate");
                    if (traffic.isDone()) traffic.get();
                    RestartDatabase.await(() -> generator.snapshot().isReady(), 8, "Session failed to recover");
                    assertEquals(session, admin.exists(node, false).getEphemeralOwner());
                }
                server.closeSession(session);
                RestartDatabase.await(() -> !generator.snapshot().isReady(), 5, "Expiry not observed");
                RestartDatabase.await(() -> generator.snapshot().getState().equals("COORDINATION"), 8, "Expiry did not become terminal");
            } finally {
                stop.set(true); traffic.get(3, TimeUnit.SECONDS);
            }
            // Drain in-flight work before asserting rejection of all subsequent calls.
            for (int i = 0; i < 100; i++) assertEquals(IdException.Reason.COORDINATION,
                    assertThrows(IdException.class, generator::getUniqueId).getReason());
            assertEquals(startupConnections, database.opened.get(), "Traffic or reconnect performed runtime JDBC");
            try (IdGenerator replacement = new IdGeneratorFactory().create(properties(database, group, "/traffic"), database.tracked)) {
                for (int i = 0; i < 100; i++) assertTrue(ids.add(replacement.getUniqueId()));
                assertThrows(IdException.class, generator::getUniqueId);
            }
            System.out.println("ZK_TRAFFIC disconnects=5 sessionExpiry=1 subsequentRejected=100 duplicates=0 generated=" + generated.get());
        } finally {
            stop.set(true); consumers.shutdownNow(); assertTrue(consumers.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(0, database.active.get());
    }
}
