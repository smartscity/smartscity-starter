package com.smartscity.id.autoconfigure.jdbc;

import com.smartscity.id.api.IdException;
import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.MonotonicClock;
import com.smartscity.id.spi.TimeSource;
import javax.sql.DataSource;
import java.sql.*;

/** JDBC is used only during startup and best-effort shutdown. Schema is operator-managed. */
public final class JdbcWorkerRepository {
    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final int timeoutSeconds;
    public JdbcWorkerRepository(DataSource dataSource, DatabaseDialect dialect, int timeoutSeconds) {
        this.dataSource = dataSource; this.dialect = dialect; this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }
    private PreparedStatement statement(Connection connection, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try { ps.setQueryTimeout(timeoutSeconds); return ps; }
        catch (SQLException | RuntimeException | Error failure) {
            try { ps.close(); } catch (SQLException | RuntimeException | Error cleanup) { suppress(failure, cleanup); }
            throw failure;
        }
    }
    private static void suppress(Throwable failure, Throwable cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); }
        catch (SQLException | RuntimeException | Error cleanup) { suppress(failure, cleanup); }
    }
    public void validateGroup(String group, BitsAllocator bits) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = statement(connection, dialect.insertGroup())) {
                    ps.setString(1, group); ps.setString(2, bits.signature());
                    ps.setLong(3, BitsAllocator.EPOCH.getEpochSecond()); ps.executeUpdate();
                }
                try (PreparedStatement ps = statement(connection,
                        "SELECT layout, epoch_second FROM smartscity_id_group WHERE group_name=? FOR UPDATE")) {
                    ps.setString(1, group);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !bits.signature().equals(rs.getString(1))
                                || rs.getLong(2) != BitsAllocator.EPOCH.getEpochSecond()) {
                            throw new IdException(IdException.Reason.CONFIGURATION, "ID group layout/Epoch mismatch");
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException | Error e) { rollback(connection, e); throw e; }
        }
    }
    public void checkTime(long toleranceMillis) throws SQLException {
        checkTime(new MonotonicClock(TimeSource.SYSTEM, toleranceMillis), toleranceMillis);
    }
    public void checkTime(MonotonicClock clock, long toleranceMillis) throws SQLException {
        long startNanos = System.nanoTime(), startWall = clock.millis();
        long databaseMillis;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = statement(connection, dialect.timeQuery()); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("Database returned no time");
            databaseMillis = rs.getLong(1);
            if (rs.wasNull()) throw new SQLException("Database returned null time");
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        long elapsed = elapsedNanos / 1_000_000;
        long endWall = clock.millis();
        if (elapsedNanos < 0 || elapsed > toleranceMillis) {
            throw new IdException(IdException.Reason.CLOCK, "Database/local clock validation failed");
        }
        try {
            // Conservative bound without subtract/abs overflow accepting an invalid timestamp.
            long midpoint = Math.addExact(startWall, elapsed / 2);
            long localDrift = Math.subtractExact(Math.subtractExact(endWall, startWall), elapsed);
            long databaseDrift = Math.subtractExact(databaseMillis, midpoint);
            long remainingTolerance = toleranceMillis - (elapsed + 1) / 2;
            if (localDrift > toleranceMillis || localDrift < -toleranceMillis
                    || databaseDrift > remainingTolerance || databaseDrift < -remainingTolerance) {
                throw new IdException(IdException.Reason.CLOCK, "Database/local clock validation failed");
            }
        } catch (ArithmeticException overflow) {
            throw new IdException(IdException.Reason.CLOCK, "Database/local clock arithmetic overflow", overflow);
        }
    }
    public long insert(String group) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO smartscity_id_worker (group_name, state) VALUES (?, 'ONLINE')", new String[] {"id"})) {
                ps.setQueryTimeout(timeoutSeconds); ps.setString(1, group); ps.executeUpdate();
                long id;
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next() || (id = rs.getLong(1)) <= 0) throw new SQLException("No positive generated worker ID");
                }
                connection.commit(); return id;
            } catch (SQLException | RuntimeException | Error e) { rollback(connection, e); throw e; }
        }
    }
    public void offline(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = statement(connection,
                "UPDATE smartscity_id_worker SET state='OFFLINE', stopped_at=CURRENT_TIMESTAMP WHERE id=?")) {
            connection.setAutoCommit(true); ps.setLong(1, id); ps.executeUpdate();
        }
    }
}
