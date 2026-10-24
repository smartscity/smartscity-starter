package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdException;
import com.smartscity.id.autoconfigure.jdbc.*;
import com.smartscity.id.core.BitsAllocator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Faults in driver boundaries; H2 is only the resource/transaction fixture. */
class LifecycleReviewTest {
    interface Call { Object invoke(Method method, Object[] args) throws Throwable; }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, method, args) -> call.invoke(method, args)));
    }
    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    JdbcDataSource database() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:review" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE smartscity_id_group(group_name VARCHAR(64) PRIMARY KEY, layout VARCHAR(32), epoch_second BIGINT)");
            s.execute("CREATE TABLE smartscity_id_worker(id BIGINT AUTO_INCREMENT PRIMARY KEY, group_name VARCHAR(64), state VARCHAR(16), stopped_at TIMESTAMP)");
        }
        return ds;
    }
    @Test void statementSetupFailureClosesStatementAndPreservesCause() {
        AtomicInteger closed = new AtomicInteger();
        SQLException expected = new SQLException("query timeout unsupported");
        PreparedStatement statement = proxy(PreparedStatement.class, (m, a) -> {
            if (m.getName().equals("setQueryTimeout")) throw expected;
            if (m.getName().equals("close")) closed.incrementAndGet();
            return null;
        });
        Connection connection = proxy(Connection.class, (m, a) -> {
            if (m.getName().equals("prepareStatement")) return statement;
            if (m.getName().equals("rollback")) throw new SQLException("rollback failed");
            return null;
        });
        DataSource ds = proxy(DataSource.class, (m, a) -> connection);
        SQLException actual = assertThrows(SQLException.class, () -> new JdbcWorkerRepository(ds, DatabaseDialect.MYSQL, 1)
                .validateGroup("review", BitsAllocator.standard()));
        assertSame(expected, actual);
        assertEquals(1, closed.get());
        assertEquals("rollback failed", actual.getSuppressed()[0].getMessage());
    }
    @Test @Timeout(10) void fatalStartupFailureAfterAllocationStillMarksWorkerOffline() throws Exception {
        JdbcDataSource ds = database();
        DataSource faulty = proxy(DataSource.class, (m, a) -> {
            Object result = invoke(ds, m, a);
            if (!m.getName().equals("getConnection")) return result;
            return proxy(Connection.class, (cm, ca) -> {
                Object value = invoke(result, cm, ca);
                if (!cm.getName().equals("prepareStatement") || !ca[0].toString().contains("UNIX_TIMESTAMP")) return value;
                return proxy(PreparedStatement.class, (sm, sa) -> {
                    if (sm.getName().equals("executeQuery")) throw new LinkageError("driver linkage failure");
                    return invoke(value, sm, sa);
                });
            });
        });
        IdProperties p = new IdProperties(); p.setBufferSize(8);
        IdException failure = assertThrows(IdException.class, () -> new IdGeneratorFactory().create(p, faulty));
        assertInstanceOf(LinkageError.class, failure.getCause());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT state FROM smartscity_id_worker")) {
            assertTrue(r.next()); assertEquals("OFFLINE", r.getString(1)); assertFalse(r.next());
        }
    }
    @Test @Timeout(10) void canceledStartupCannotActivateAfterDriverReturnsLate() throws Exception {
        JdbcDataSource ds = database(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger activeConnections = new AtomicInteger();
        DataSource delayed = proxy(DataSource.class, (m, a) -> {
            Object result = invoke(ds, m, a);
            if (!m.getName().equals("getConnection")) return result;
            activeConnections.incrementAndGet();
            return proxy(Connection.class, (cm, ca) -> {
                if (cm.getName().equals("close")) activeConnections.decrementAndGet();
                Object value = invoke(result, cm, ca);
                if (!cm.getName().equals("prepareStatement") || !ca[0].toString().contains("UNIX_TIMESTAMP")) return value;
                return proxy(PreparedStatement.class, (sm, sa) -> {
                    if (sm.getName().equals("executeQuery")) {
                        entered.countDown(); boolean interrupted = false;
                        while (true) { try { release.await(); break; } catch (InterruptedException e) { interrupted = true; } }
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                    return invoke(value, sm, sa);
                });
            });
        });
        IdProperties p = new IdProperties(); p.setStartupTimeout(Duration.ofMillis(300)); p.setBufferSize(8);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> startup = caller.submit(() -> assertThrows(IdException.class, () -> new IdGeneratorFactory().create(p, delayed)));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); startup.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); caller.shutdownNow(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        String state;
        do {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT state FROM smartscity_id_worker")) { assertTrue(r.next()); state = r.getString(1); }
            if (state.equals("OFFLINE") && activeConnections.get() == 0) break;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        assertEquals("OFFLINE", state); assertEquals(0, activeConnections.get());
    }
}
