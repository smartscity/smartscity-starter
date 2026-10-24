package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.IdException;
import com.smartscity.id.autoconfigure.jdbc.*;
import com.smartscity.id.core.MonotonicClock;
import com.smartscity.id.spi.TimeSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.atomic.*;
import static com.smartscity.id.autoconfigure.LifecycleReviewTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic driver responses verify validation and cleanup, not database compatibility. */
@Timeout(10)
class ClockValidationTest {
    private static final long BASE = 1_800_000_000_000L;
    private static final class Time implements TimeSource {
        volatile long wall;
        Time(long wall) { this.wall = wall; }
        public long wallTimeMillis() { return wall; }
        public long nanoTime() { return 0; }
    }
    private static final class Query {
        final AtomicInteger connectionsClosed = new AtomicInteger(), statementsClosed = new AtomicInteger(), resultsClosed = new AtomicInteger();
        long value; boolean row = true, sqlNull; Runnable onQuery = () -> {};
        Query(long value) { this.value = value; }
        final ResultSet result = proxy(ResultSet.class, (m, a) -> {
            switch (m.getName()) {
                case "next": return row;
                case "getLong": return value;
                case "wasNull": return sqlNull;
                case "close": resultsClosed.incrementAndGet(); return null;
                default: throw new AssertionError(m.getName());
            }
        });
        final PreparedStatement statement = proxy(PreparedStatement.class, (m, a) -> {
            switch (m.getName()) {
                case "setQueryTimeout": return null;
                case "executeQuery": onQuery.run(); return result;
                case "close": statementsClosed.incrementAndGet(); return null;
                default: throw new AssertionError(m.getName());
            }
        });
        final Connection connection = proxy(Connection.class, (m, a) -> {
            switch (m.getName()) {
                case "prepareStatement": return statement;
                case "close": connectionsClosed.incrementAndGet(); return null;
                default: throw new AssertionError(m.getName());
            }
        });
        final DataSource dataSource = proxy(DataSource.class, (m, a) -> {
            if (m.getName().equals("getConnection")) return connection;
            throw new AssertionError(m.getName());
        });
        void check(Time time, long tolerance) throws SQLException {
            new JdbcWorkerRepository(dataSource, DatabaseDialect.MYSQL, 1)
                    .checkTime(new MonotonicClock(time, tolerance), tolerance);
        }
        void assertClosed() {
            assertEquals(1, connectionsClosed.get()); assertEquals(1, statementsClosed.get()); assertEquals(1, resultsClosed.get());
        }
    }
    private static void clockFailure(org.junit.jupiter.api.function.Executable call) {
        assertEquals(IdException.Reason.CLOCK, assertThrows(IdException.class, call).getReason());
    }
    @Test void healthyDatabaseTimeIsAcceptedAndEveryResourceIsClosed() throws Exception {
        Query query = new Query(BASE); query.check(new Time(BASE), 1000); query.assertClosed();
    }
    @Test void databaseTimeOutsideEitherToleranceEdgeIsRejected() {
        for (long offset : new long[] {-1001, 1001}) {
            Query query = new Query(BASE + offset);
            clockFailure(() -> query.check(new Time(BASE), 1000)); query.assertClosed();
        }
    }
    @Test void extremeTimestampCannotPassThroughSubtractionOrAbsoluteValueOverflow() {
        for (long wall : new long[] {0, Long.MAX_VALUE}) {
            Query query = new Query(Long.MIN_VALUE);
            clockFailure(() -> query.check(new Time(wall), 1000)); query.assertClosed();
        }
    }
    @Test void missingAndNullDatabaseTimeAreRejectedAndClosed() {
        for (boolean nullValue : new boolean[] {false, true}) {
            Query query = new Query(0); query.row = nullValue; query.sqlNull = nullValue;
            assertThrows(SQLException.class, () -> query.check(new Time(0), 1000)); query.assertClosed();
        }
    }
    @Test void wallClockJumpDuringDatabaseQueryUsesTheOriginalAnchor() {
        for (long offset : new long[] {-2000, 2000}) {
            Time time = new Time(BASE); Query query = new Query(BASE);
            query.onQuery = () -> time.wall += offset;
            clockFailure(() -> query.check(time, 1000)); query.assertClosed();
        }
    }
    @Test void excessiveQueryRoundTripFailsClosed() {
        Query query = new Query(BASE);
        query.onQuery = () -> {
            try { Thread.sleep(35); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        };
        clockFailure(() -> query.check(new Time(BASE), 10)); query.assertClosed();
    }
    @Test void startupClockRejectionMarksAllocatedWorkerOfflineAndClosesConnections() throws Exception {
        for (long offset : new long[] {-10000, 10000}) {
            JdbcDataSource ds = new LifecycleReviewTest().database(); AtomicInteger active = new AtomicInteger();
            DataSource faulty = proxy(DataSource.class, (m, a) -> {
                Object result = invoke(ds, m, a);
                if (!m.getName().equals("getConnection")) return result;
                active.incrementAndGet();
                return proxy(Connection.class, (cm, ca) -> {
                    if (cm.getName().equals("close")) active.decrementAndGet();
                    Object value = invoke(result, cm, ca);
                    if (!cm.getName().equals("prepareStatement") || !ca[0].toString().contains("UNIX_TIMESTAMP")) return value;
                    return proxy(PreparedStatement.class, (sm, sa) -> {
                        if (sm.getName().equals("executeQuery")) return new Query(System.currentTimeMillis() + offset).result;
                        return invoke(value, sm, sa);
                    });
                });
            });
            IdProperties properties = new IdProperties(); properties.setBufferSize(8);
            clockFailure(() -> new IdGeneratorFactory().create(properties, faulty));
            assertEquals(0, active.get());
            try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT state FROM smartscity_id_worker")) {
                assertTrue(result.next()); assertEquals("OFFLINE", result.getString(1)); assertFalse(result.next());
            }
        }
    }
}
