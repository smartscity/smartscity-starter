package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.autoconfigure.jdbc.DatabaseDialect;
import com.smartscity.id.autoconfigure.jdbc.JdbcWorkerRepository;
import com.smartscity.id.autoconfigure.jdbc.JdbcWorkerRepository.Registration;
import com.smartscity.id.autoconfigure.jdbc.WorkerInstance;
import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.IdLayout;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises registry invariants on H2 and supplies the same checks to real database tests.
 *
 * @author jack
 */
class JdbcIntegrationTest {
  private DataSource ds;

  @BeforeEach
  void schema() throws Exception {
    ds = new LifecycleReviewTest().database();
  }

  static WorkerInstance instance(String id) {
    return new WorkerInstance(id, "registry-test", null, null, null);
  }

  @Test
  @Timeout(20)
  void concurrentBoundedRegistrationAndNonReuse() throws Exception {
    exerciseRegistry(ds, DatabaseDialect.MYSQL);
  }

  static void exerciseRegistry(DataSource ds, DatabaseDialect dialect) throws Exception {
    String group = "registry_" + System.nanoTime();
    BitsAllocator bits = new BitsAllocator(new IdLayout(IdLayout.DEFAULT_EPOCH, 30, 2, 31));
    JdbcWorkerRepository repo = new JdbcWorkerRepository(ds, dialect, 10);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    Set<Long> workers = ConcurrentHashMap.newKeySet();
    AtomicInteger exhausted = new AtomicInteger();
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        jobs.add(
            pool.submit(
                () -> {
                  try {
                    Registration r = repo.register(group, bits, instance(null));
                    assertTrue(workers.add(r.getWorkerId()));
                    // Include a stale REGISTERED record and a stale ACTIVE record. Neither is
                    // reusable.
                    if (r.getWorkerId() > 0) {
                      repo.started(r);
                    }
                    if (r.getWorkerId() > 1) {
                      repo.stopped(r, true);
                    }
                  } catch (IdException e) {
                    assertEquals(IdException.Reason.CAPACITY, e.getReason());
                    exhausted.incrementAndGet();
                  } catch (SQLException e) {
                    throw new RuntimeException(e);
                  }
                }));
      }
      for (Future<?> job : jobs) {
        job.get(15, TimeUnit.SECONDS);
      }
      assertEquals(Set.of(0L, 1L, 2L, 3L), workers);
      assertEquals(4, exhausted.get());
      assertEquals(
          IdException.Reason.CAPACITY,
          assertThrows(IdException.class, () -> repo.register(group, bits, instance(null)))
              .getReason());
    } finally {
      pool.shutdownNow();
    }

    String identityGroup = group + "_identity";
    Registration first = repo.register(identityGroup, bits, instance("same-process"));
    assertEquals(0, first.getWorkerId());
    assertTrue(first.getId() > 0);
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT instance_id, application_name, host_ip, host_port, hostname, status, registered_at, started_at, updated_at FROM smartscity_id_worker WHERE id=?")) {
      ps.setLong(1, first.getId());
      try (ResultSet r = ps.executeQuery()) {
        assertTrue(r.next());
        assertEquals("same-process", r.getString(1));
        assertEquals("registry-test", r.getString(2));
        org.junit.jupiter.api.Assertions.assertNull(r.getString(3));
        org.junit.jupiter.api.Assertions.assertNull(r.getObject(4));
        org.junit.jupiter.api.Assertions.assertNull(r.getString(5));
        assertEquals("REGISTERED", r.getString(6));
        org.junit.jupiter.api.Assertions.assertNotNull(r.getTimestamp(7));
        org.junit.jupiter.api.Assertions.assertNull(r.getTimestamp(8));
        org.junit.jupiter.api.Assertions.assertNotNull(r.getTimestamp(9));
      }
    }
    repo.stopped(first, false);
    assertEquals(
        IdException.Reason.COORDINATION,
        assertThrows(
                IdException.class,
                () -> repo.register(identityGroup, bits, instance("same-process")))
            .getReason());
    assertEquals(
        IdException.Reason.CONFIGURATION,
        assertThrows(
                IdException.class,
                () -> repo.register(identityGroup, BitsAllocator.standard(), instance(null)))
            .getReason());
    Registration second = repo.register(identityGroup, bits, instance(null));
    assertEquals(
        1,
        second.getWorkerId(),
        "failed and mismatched registrations cannot recycle the first worker");
    try (Connection c = ds.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE smartscity_id_worker SET registration_token=? WHERE id=?")) {
      ps.setString(1, java.util.UUID.randomUUID().toString());
      ps.setLong(2, second.getId());
      assertEquals(1, ps.executeUpdate());
    }
    assertEquals(
        IdException.Reason.COORDINATION,
        assertThrows(IdException.class, () -> repo.started(second)).getReason());
    assertEquals(
        IdException.Reason.COORDINATION,
        assertThrows(IdException.class, () -> repo.stopped(second, false)).getReason());
    assertEquals(2, repo.register(identityGroup, bits, instance(null)).getWorkerId());
  }

  @Test
  void nullRegisteredEpochIsNotAcceptedAsUnixEpoch() throws Exception {
    JdbcWorkerRepository repo = new JdbcWorkerRepository(ds, DatabaseDialect.MYSQL, 2);
    repo.validateGroup("broken", BitsAllocator.standard());
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      s.execute("ALTER TABLE smartscity_id_group ALTER COLUMN epoch_second DROP NOT NULL");
      s.execute("UPDATE smartscity_id_group SET epoch_second=NULL");
    }
    BitsAllocator unix = new BitsAllocator(new IdLayout(java.time.Instant.EPOCH, 30, 18, 15));
    assertThrows(IdException.class, () -> repo.validateGroup("broken", unix));
  }

  @Test
  void insertFailureRollsBackCursorAndClosesBorrowedResources() throws Exception {
    AtomicInteger connections = new AtomicInteger();
    AtomicInteger statements = new AtomicInteger();
    DataSource faulty =
        LifecycleReviewTest.proxy(
            DataSource.class,
            (m, a) -> {
              Object result = LifecycleReviewTest.invoke(ds, m, a);
              if (!m.getName().equals("getConnection")) {
                return result;
              }
              connections.incrementAndGet();
              return LifecycleReviewTest.proxy(
                  Connection.class,
                  (cm, ca) -> {
                    if (cm.getName().equals("close")) {
                      connections.decrementAndGet();
                    }
                    Object value = LifecycleReviewTest.invoke(result, cm, ca);
                    if (!cm.getName().equals("prepareStatement")) {
                      return value;
                    }
                    statements.incrementAndGet();
                    return LifecycleReviewTest.proxy(
                        PreparedStatement.class,
                        (sm, sa) -> {
                          if (sm.getName().equals("close")) {
                            statements.decrementAndGet();
                          }
                          if (sm.getName().equals("executeUpdate")
                              && ca[0].toString().startsWith("INSERT INTO smartscity_id_worker")) {
                            throw new SQLException("injected insert failure");
                          }
                          return LifecycleReviewTest.invoke(value, sm, sa);
                        });
                  });
            });
    JdbcWorkerRepository repo = new JdbcWorkerRepository(ds, DatabaseDialect.MYSQL, 2);
    repo.validateGroup("rollback", BitsAllocator.standard());
    assertThrows(
        SQLException.class,
        () ->
            new JdbcWorkerRepository(faulty, DatabaseDialect.MYSQL, 2)
                .register("rollback", BitsAllocator.standard(), instance(null)));
    assertEquals(0, connections.get());
    assertEquals(0, statements.get());
    assertEquals(
        0, repo.register("rollback", BitsAllocator.standard(), instance(null)).getWorkerId());
  }

  @Test
  void unknownCommitCannotReturnOrRecycleWorker() throws Exception {
    DataSource uncertain =
        LifecycleReviewTest.proxy(
            DataSource.class,
            (m, a) -> {
              Object result = LifecycleReviewTest.invoke(ds, m, a);
              if (!m.getName().equals("getConnection")) {
                return result;
              }
              return LifecycleReviewTest.proxy(
                  Connection.class,
                  (cm, ca) -> {
                    Object value = LifecycleReviewTest.invoke(result, cm, ca);
                    if (cm.getName().equals("commit")) {
                      throw new SQLException("reply lost after commit");
                    }
                    return value;
                  });
            });
    assertThrows(
        SQLException.class,
        () ->
            new JdbcWorkerRepository(uncertain, DatabaseDialect.MYSQL, 2)
                .register("unknown", BitsAllocator.standard(), instance(null)));
    JdbcWorkerRepository repo = new JdbcWorkerRepository(ds, DatabaseDialect.MYSQL, 2);
    assertEquals(
        1, repo.register("unknown", BitsAllocator.standard(), instance(null)).getWorkerId());
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet r =
            s.executeQuery("SELECT COUNT(*) FROM smartscity_id_worker WHERE status='REGISTERED'")) {
      assertTrue(r.next());
      assertEquals(2, r.getInt(1));
    }
  }
}
