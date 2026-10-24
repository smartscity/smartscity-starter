package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import com.smartscity.id.autoconfigure.jdbc.*;
import com.smartscity.id.core.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** H2 tests transaction mechanics only; not a claim of MySQL/PostgreSQL/TiDB compatibility. */
class JdbcIntegrationTest {
    private JdbcDataSource ds;
    @BeforeEach void schema() throws Exception {
        ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:id" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE smartscity_id_group(group_name VARCHAR(64) PRIMARY KEY, layout VARCHAR(32), epoch_second BIGINT)");
            s.execute("CREATE TABLE smartscity_id_worker(id BIGINT AUTO_INCREMENT PRIMARY KEY, group_name VARCHAR(64), state VARCHAR(16), stopped_at TIMESTAMP)");
        }
    }
    @Test void groupMismatchRollsBackAndAutoIncrementIsNotReusedAfterOffline() throws Exception {
        JdbcWorkerRepository repo = new JdbcWorkerRepository(ds, DatabaseDialect.MYSQL, 2);
        repo.validateGroup("test", BitsAllocator.standard());
        assertThrows(IdException.class, () -> repo.validateGroup("test", BitsAllocator.highThroughput()));
        long a = repo.insert("test"); repo.offline(a); long b = repo.insert("test"); assertTrue(b > a);
        try (Connection c = ds.getConnection(); ResultSet rs = c.createStatement().executeQuery("SELECT state FROM smartscity_id_worker WHERE id=" + a)) {
            assertTrue(rs.next()); assertEquals("OFFLINE", rs.getString(1));
        }
    }
    @Test @Timeout(15) void concurrentStartupAllocatesDistinctRawIds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4); Set<Long> ids = ConcurrentHashMap.newKeySet();
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) jobs.add(pool.submit(() -> {
                JdbcWorkerRepository repo = new JdbcWorkerRepository(ds, DatabaseDialect.MYSQL, 5);
                try { repo.validateGroup("test", BitsAllocator.standard()); for (int j = 0; j < 10; j++) assertTrue(ids.add(repo.insert("test"))); }
                catch (SQLException e) { throw new RuntimeException(e); }
            }));
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS); assertEquals(40, ids.size());
        } finally { pool.shutdownNow(); }
    }
}
