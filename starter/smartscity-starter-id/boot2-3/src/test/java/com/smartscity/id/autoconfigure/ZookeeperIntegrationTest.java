package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.autoconfigure.zk.ZookeeperWorkerOccupancy;
import com.smartscity.id.spi.WorkerLease;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real ZK server and TCP clients in an isolated test directory.
 *
 * @author jack
 */
class ZookeeperIntegrationTest {
  @TempDir Path directory;
  ZooKeeperServer server;
  NIOServerCnxnFactory network;
  String address;

  @BeforeEach
  void start() throws Exception {
    System.setProperty("zookeeper.nio.numWorkerThreads", "2");
    System.setProperty("zookeeper.nio.numSelectorThreads", "1");
    server =
        new ZooKeeperServer(
            directory.resolve("snap").toFile(), directory.resolve("log").toFile(), 500);
    network = new NIOServerCnxnFactory();
    network.configure(new InetSocketAddress("127.0.0.1", 0), 20);
    network.startup(server);
    address = "127.0.0.1:" + network.getLocalPort();
  }

  @AfterEach
  void stop() {
    if (network != null) {
      network.shutdown();
    }
    if (server != null) {
      server.shutdown();
    }
  }

  ZookeeperWorkerOccupancy client() throws Exception {
    return new ZookeeperWorkerOccupancy(address, "/test/workers", 10000, 5000, null, null);
  }

  @Test
  @Timeout(15)
  void interruptedClosePreservesTheCallersInterruptFlag() throws Exception {
    try (ZookeeperWorkerOccupancy owner = client()) {
      assertTrue(owner.tryOccupy(11, 11));
      Thread.currentThread().interrupt();
      try {
        owner.close();
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals(WorkerLease.State.CLOSED, owner.state());
      assertTrue(clientThreadsStopped(owner), "Client threads must actually exit");
    }
  }

  @Test
  @Timeout(15)
  void unresponsiveServerDoesNotHoldClosePastBudget() throws Exception {
    try (ZookeeperWorkerOccupancy owner =
        new ZookeeperWorkerOccupancy(address, "/test/workers", 10000, 1000, null, null)) {
      assertTrue(owner.tryOccupy(12, 12));
      // Real TCP stays connected but the server stops receiving requests, including closeSession.
      java.lang.reflect.Method disable = ServerCnxn.class.getDeclaredMethod("disableRecv");
      disable.setAccessible(true);
      for (ServerCnxn connection : network.getConnections()) {
        disable.invoke(connection);
      }
      long start = System.nanoTime();
      owner.close();
      assertTrue(
          System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "Whole close must be bounded");
      assertEquals(WorkerLease.State.CLOSED, owner.state());
      assertTrue(clientThreadsStopped(owner), "Timeout must still initiate client cleanup");
    }
  }

  private boolean clientThreadsStopped(ZookeeperWorkerOccupancy owner) throws Exception {
    java.lang.reflect.Field field = ZookeeperWorkerOccupancy.class.getDeclaredField("client");
    field.setAccessible(true);
    java.lang.reflect.Method wait =
        ZooKeeper.class.getDeclaredMethod("testableWaitForShutdown", int.class);
    wait.setAccessible(true);
    return (boolean) wait.invoke(field.get(owner), 2000);
  }

  @Test
  @Timeout(15)
  void failedZookeeperClaimCleansTheAlreadyCommittedWorker() throws Exception {
    try (ZooKeeper admin = new ZooKeeper(address, 10000, event -> {})) {
      admin.create("/denied", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
      admin.create(
          "/denied/review", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
      admin.create(
          "/denied/review/workers",
          new byte[0],
          ZooDefs.Ids.OPEN_ACL_UNSAFE,
          CreateMode.PERSISTENT);
      admin.setACL(
          "/denied/review/workers",
          java.util.Collections.singletonList(
              new org.apache.zookeeper.data.ACL(ZooDefs.Perms.READ, ZooDefs.Ids.ANYONE_ID_UNSAFE)),
          -1);
      javax.sql.DataSource ds = new LifecycleReviewTest().database();
      IdProperties properties = new IdProperties();
      properties.setProfile("javascript");
      properties.setGroup("review");
      properties.getZookeeper().setConnectString(address);
      properties.getZookeeper().setRoot("/denied");
      com.smartscity.id.api.IdException error =
          assertThrows(
              com.smartscity.id.api.IdException.class,
              () -> new IdGeneratorFactory().create(properties, ds));
      assertInstanceOf(KeeperException.NoAuthException.class, error.getCause());
      try (java.sql.Connection connection = ds.getConnection();
          java.sql.Statement statement = connection.createStatement();
          java.sql.ResultSet result =
              statement.executeQuery("SELECT status FROM smartscity_id_worker")) {
        assertTrue(result.next());
        assertEquals("FAILED", result.getString(1));
        assertFalse(result.next());
      }
    }
  }

  @Test
  @Timeout(15)
  void sameEncodedWorkerCannotBeOccupiedConcurrentlyAndCanBeReused() throws Exception {
    try (ZookeeperWorkerOccupancy a = client();
        ZookeeperWorkerOccupancy b = client()) {
      assertTrue(a.tryOccupy(7, 7));
      assertFalse(b.tryOccupy(7, 1031));
      assertEquals(WorkerLease.State.ACTIVE, a.state());
      a.close();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      boolean acquired = false;
      while (!(acquired = b.tryOccupy(7, 1031)) && System.nanoTime() < end) {
        Thread.sleep(10);
      }
      assertTrue(acquired);
      assertEquals(WorkerLease.State.ACTIVE, b.state());
    }
  }

  @Test
  @Timeout(15)
  void deletionPermanentlyExpiresOwner() throws Exception {
    try (ZookeeperWorkerOccupancy owner = client();
        ZooKeeper admin = new ZooKeeper(address, 2000, event -> {})) {
      assertTrue(owner.tryOccupy(1, 1));
      admin.delete("/test/workers/1", -1);
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (owner.state() != WorkerLease.State.EXPIRED && System.nanoTime() < end) {
        Thread.sleep(10);
      }
      assertEquals(WorkerLease.State.EXPIRED, owner.state());
    }
  }

  @Test
  @Timeout(15)
  void networkDisconnectSuspendsThenSameSessionCanResume() throws Exception {
    try (ZookeeperWorkerOccupancy owner = client()) {
      assertTrue(owner.tryOccupy(5, 5));
      network.closeAll(ServerCnxn.DisconnectReason.SERVER_SHUTDOWN);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean suspended = false;
      while (System.nanoTime() < deadline) {
        if (owner.state() == WorkerLease.State.SUSPENDED) {
          suspended = true;
          break;
        }
        Thread.sleep(1);
      }
      assertTrue(suspended, "Disconnect must close the local gate");
      while (owner.state() == WorkerLease.State.SUSPENDED && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertEquals(WorkerLease.State.ACTIVE, owner.state());
    }
  }

  @Test
  @Timeout(15)
  void serverSessionExpiryCannotReviveOldOwner() throws Exception {
    try (ZookeeperWorkerOccupancy owner = client();
        ZooKeeper admin = new ZooKeeper(address, 2000, e -> {})) {
      assertTrue(owner.tryOccupy(9, 9));
      long session = admin.exists("/test/workers/9", false).getEphemeralOwner();
      server.closeSession(session);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (owner.state() != WorkerLease.State.EXPIRED && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(WorkerLease.State.EXPIRED, owner.state());
      try (ZookeeperWorkerOccupancy next = client()) {
        assertTrue(next.tryOccupy(9, 1033));
        assertEquals(WorkerLease.State.EXPIRED, owner.state());
      }
    }
  }

  @Test
  @Timeout(20)
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "id.test.mysql.url",
      matches = ".+")
  void javascriptSdkIntegratesMysqlZookeeperAndCachedStopGate() throws Exception {
    IdProperties p = new IdProperties();
    p.setProfile("javascript");
    p.setGroup("js_" + System.nanoTime());
    p.setBufferSize(64);
    p.getDatabase().setUrl(System.getProperty("id.test.mysql.url"));
    p.getDatabase().setUsername("root");
    p.getZookeeper().setConnectString(address);
    p.getZookeeper().setRoot("/integrated");
    try (com.smartscity.id.api.IdGenerator first = new IdGeneratorFactory().create(p, null);
        com.smartscity.id.api.IdGenerator second = new IdGeneratorFactory().create(p, null);
        ZooKeeper admin = new ZooKeeper(address, 10000, e -> {})) {
      long id = first.getUniqueId();
      assertTrue(id > 0 && id <= (1L << 53) - 1);
      assertNotEquals(id, second.getUniqueId());
      String node = "/integrated/" + p.getGroup() + "/workers/" + first.snapshot().getWorkerId();
      server.closeSession(admin.exists(node, false).getEphemeralOwner());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (first.snapshot().isReady() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertFalse(first.snapshot().isReady());
      assertEquals(
          com.smartscity.id.api.IdException.Reason.COORDINATION,
          assertThrows(com.smartscity.id.api.IdException.class, first::getUniqueId).getReason());
      assertTrue(second.getUniqueId() > 0);
    }
  }
}
