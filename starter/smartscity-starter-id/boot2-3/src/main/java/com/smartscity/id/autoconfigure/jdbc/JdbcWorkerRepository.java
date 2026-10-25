package com.smartscity.id.autoconfigure.jdbc;

import com.smartscity.id.api.IdException;
import com.smartscity.id.core.BitsAllocator;
import com.smartscity.id.core.MonotonicClock;
import com.smartscity.id.spi.TimeSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * JDBC is used only during startup and best-effort shutdown. Schema is operator-managed.
 *
 * @author jack
 */
public final class JdbcWorkerRepository {
  private final DataSource dataSource;
  private final DatabaseDialect dialect;
  private final int timeoutSeconds;

  /**
   * Creates an operation-scoped repository without borrowing a connection.
   *
   * @param dataSource externally owned, never closed by this repository
   * @param dialect database clock-query dialect
   * @param timeoutSeconds JDBC statement timeout, clamped to at least one second
   */
  public JdbcWorkerRepository(DataSource dataSource, DatabaseDialect dialect, int timeoutSeconds) {
    this.dataSource = dataSource;
    this.dialect = dialect;
    this.timeoutSeconds = Math.max(1, timeoutSeconds);
  }

  private PreparedStatement statement(Connection connection, String sql) throws SQLException {
    PreparedStatement ps = connection.prepareStatement(sql);
    try {
      ps.setQueryTimeout(timeoutSeconds);
      return ps;
    } catch (SQLException | RuntimeException | Error failure) {
      try {
        ps.close();
      } catch (SQLException | RuntimeException | Error cleanup) {
        suppress(failure, cleanup);
      }
      throw failure;
    }
  }

  private static void suppress(Throwable failure, Throwable cleanup) {
    if (failure != cleanup) {
      failure.addSuppressed(cleanup);
    }
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException | RuntimeException | Error cleanup) {
      suppress(failure, cleanup);
    }
  }

  /**
   * Validates or creates format metadata without allocating a worker. Connections and transaction
   * ownership end before this method returns.
   */
  public void validateGroup(String group, BitsAllocator bits) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        lockGroup(connection, group, bits);
        connection.commit();
      } catch (SQLException | RuntimeException | Error e) {
        rollback(connection, e);
        throw e;
      }
    }
  }

  private long[] lockGroup(Connection connection, String group, BitsAllocator bits)
      throws SQLException {
    if (group == null || !group.matches("[a-zA-Z0-9_-]{1,128}")) {
      throw new IdException(
          IdException.Reason.CONFIGURATION,
          "group must contain 1..128 letters, digits, underscores or hyphens");
    }
    java.sql.Savepoint savepoint = connection.setSavepoint();
    try (PreparedStatement ps =
        statement(
            connection,
            "INSERT INTO smartscity_id_group (group_name, layout, epoch_second, config_version, worker_slots, next_worker_id, created_at, updated_at) VALUES (?, ?, ?, 1, ?, 0, ?, ?)")) {
      ps.setString(1, group);
      ps.setString(2, bits.signature());
      ps.setLong(3, bits.getLayout().getEpoch().getEpochSecond());
      ps.setLong(4, bits.getWorkerSlots());
      timestamp(ps, 5);
      timestamp(ps, 6);
      ps.executeUpdate();
    } catch (SQLException e) {
      if (!("23505".equals(e.getSQLState())
          || ("23000".equals(e.getSQLState()) && e.getErrorCode() == 1062))) {
        throw e;
      }
      // PostgreSQL requires savepoint rollback before a statement can follow a unique conflict.
      connection.rollback(savepoint);
    }
    connection.releaseSavepoint(savepoint);
    try (PreparedStatement ps =
        statement(
            connection,
            "SELECT id, layout, epoch_second, config_version, worker_slots, next_worker_id FROM smartscity_id_group WHERE group_name=? FOR UPDATE")) {
      ps.setString(1, group);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()
            || !bits.signature().equals(rs.getString(2))
            || rs.getLong(3) != bits.getLayout().getEpoch().getEpochSecond()
            || rs.wasNull()
            || rs.getLong(4) != 1
            || rs.getLong(5) != bits.getWorkerSlots()) {
          throw new IdException(
              IdException.Reason.CONFIGURATION, "Group format/version mismatch: group=" + group);
        }
        long id = rs.getLong(1);
        long next = rs.getLong(6);
        if (rs.wasNull() || id <= 0 || next < 0 || next > bits.getWorkerSlots()) {
          throw new IdException(
              IdException.Reason.CONFIGURATION, "Invalid group allocation cursor: group=" + group);
        }
        return new long[] {id, next};
      }
    }
  }

  private static void timestamp(PreparedStatement ps, int index) throws SQLException {
    ps.setTimestamp(
        index,
        java.sql.Timestamp.from(java.time.Instant.now()),
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
  }

  /**
   * Atomically reserves a never-reused worker and records this incarnation. A receipt is returned
   * only after commit. An uncertain commit is not automatically retried.
   *
   * @throws IdException if the format conflicts, instance was registered, or worker space is
   *     exhausted
   * @throws SQLException on transaction/driver failure; all borrowed resources are closed
   */
  public Registration register(String group, BitsAllocator bits, WorkerInstance instance)
      throws SQLException {
    java.util.Objects.requireNonNull(instance, "instance");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        long[] allocation = lockGroup(connection, group, bits);
        long groupId = allocation[0];
        long worker = allocation[1];
        try (PreparedStatement ps =
            statement(
                connection,
                "SELECT id FROM smartscity_id_worker WHERE group_id=? AND instance_id=?")) {
          ps.setLong(1, groupId);
          ps.setString(2, instance.getInstanceId());
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              throw new IdException(
                  IdException.Reason.COORDINATION, "Instance already registered: group=" + group);
            }
          }
        }
        if (worker >= bits.getWorkerSlots()) {
          throw new IdException(
              IdException.Reason.CAPACITY,
              "Worker space exhausted: group=" + group + ", slots=" + bits.getWorkerSlots());
        }
        try (PreparedStatement ps =
            statement(
                connection,
                "UPDATE smartscity_id_group SET next_worker_id=?, updated_at=? WHERE id=?")) {
          ps.setLong(1, worker + 1);
          timestamp(ps, 2);
          ps.setLong(3, groupId);
          if (ps.executeUpdate() != 1) {
            throw new SQLException("Group allocation update lost");
          }
        }
        String token = java.util.UUID.randomUUID().toString();
        long raw;
        try (PreparedStatement ps =
            connection.prepareStatement(
                "INSERT INTO smartscity_id_worker (group_id, worker_id, instance_id, registration_token, application_name, host_ip, host_port, hostname, status, registered_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REGISTERED', ?, ?)",
                new String[] {"id"})) {
          ps.setQueryTimeout(timeoutSeconds);
          ps.setLong(1, groupId);
          ps.setLong(2, worker);
          ps.setString(3, instance.getInstanceId());
          ps.setString(4, token);
          ps.setString(5, instance.getApplicationName());
          ps.setString(6, instance.getHostIp());
          ps.setObject(7, instance.getHostPort(), java.sql.Types.INTEGER);
          ps.setString(8, instance.getHostname());
          timestamp(ps, 9);
          timestamp(ps, 10);
          ps.executeUpdate();
          try (ResultSet rs = ps.getGeneratedKeys()) {
            if (!rs.next() || (raw = rs.getLong(1)) <= 0) {
              throw new SQLException("Missing registration identity");
            }
          }
        }
        connection.commit();
        return new Registration(raw, groupId, worker, token);
      } catch (SQLException | RuntimeException | Error e) {
        rollback(connection, e);
        throw e;
      }
    }
  }

  /**
   * Records successful local initialization for a committed registration.
   *
   * <p>The metadata transition does not grant a lease or authorize recycling the worker number.
   *
   * @param registration committed receipt including the metadata ownership token
   * @throws IdException if the receipt or current status rejects activation, including repetition
   * @throws SQLException if the metadata update fails
   */
  public void started(Registration registration) throws SQLException {
    transition(registration, true, false);
  }

  /**
   * Records termination at most once without releasing the worker number.
   *
   * @param registration committed receipt including metadata ownership token
   * @param started whether local activation completed successfully
   * @throws IdException if the receipt or current state rejects the transition, including
   *     repetition
   * @throws SQLException if the metadata update fails
   */
  public void stopped(Registration registration, boolean started) throws SQLException {
    transition(registration, false, started);
  }

  private void transition(Registration r, boolean activating, boolean started) throws SQLException {
    String status = activating ? "ACTIVE" : started ? "STOPPED" : "FAILED";
    String timeColumn = activating ? "started_at" : "shutdown_at";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps =
            statement(
                connection,
                "UPDATE smartscity_id_worker SET status=?, "
                    + timeColumn
                    + "=?, updated_at=? WHERE id=? AND group_id=? AND worker_id=? AND registration_token=? AND "
                    + (activating
                        ? "status='REGISTERED'"
                        : "status IN ('REGISTERED', 'ACTIVE')"))) {
      connection.setAutoCommit(true);
      ps.setString(1, status);
      timestamp(ps, 2);
      timestamp(ps, 3);
      ps.setLong(4, r.id);
      ps.setLong(5, r.groupId);
      ps.setLong(6, r.workerId);
      ps.setString(7, r.token);
      if (ps.executeUpdate() != 1) {
        throw new IdException(
            IdException.Reason.COORDINATION,
            "Registration transition rejected: registrationId=" + r.id + ", target=" + status);
      }
    }
  }

  /** Committed registration receipt; its token protects metadata updates, not local issuance. */
  public static final class Registration {
    private final long id;
    private final long groupId;
    private final long workerId;
    private final String token;

    private Registration(long id, long groupId, long workerId, String token) {
      this.id = id;
      this.groupId = groupId;
      this.workerId = workerId;
      this.token = token;
    }

    public long getId() {
      return id;
    }

    public long getGroupId() {
      return groupId;
    }

    public long getWorkerId() {
      return workerId;
    }
  }

  /**
   * Validates database time against a newly captured local anchor.
   *
   * @param toleranceMillis positive maximum skew including query uncertainty
   * @throws IdException if local or database clock validation fails
   * @throws SQLException if the database query fails
   */
  public void checkTime(long toleranceMillis) throws SQLException {
    checkTime(new MonotonicClock(TimeSource.SYSTEM, toleranceMillis), toleranceMillis);
  }

  /**
   * Validates database time without replacing the generator's established clock anchor.
   *
   * @param clock anchor that will also govern local generation
   * @param toleranceMillis positive maximum skew including query uncertainty
   * @throws IdException if skew, round-trip duration or arithmetic is invalid
   * @throws SQLException if the query fails or returns no usable time
   */
  public void checkTime(MonotonicClock clock, long toleranceMillis) throws SQLException {
    long startNanos = System.nanoTime();
    long startWall = clock.millis();

    long databaseMillis;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement ps = statement(connection, dialect.timeQuery());
        ResultSet rs = ps.executeQuery()) {
      if (!rs.next()) {
        throw new SQLException("Database returned no time");
      }
      databaseMillis = rs.getLong(1);
      if (rs.wasNull()) {
        throw new SQLException("Database returned null time");
      }
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
      if (localDrift > toleranceMillis
          || localDrift < -toleranceMillis
          || databaseDrift > remainingTolerance
          || databaseDrift < -remainingTolerance) {
        throw new IdException(IdException.Reason.CLOCK, "Database/local clock validation failed");
      }
    } catch (ArithmeticException overflow) {
      throw new IdException(
          IdException.Reason.CLOCK, "Database/local clock arithmetic overflow", overflow);
    }
  }
}
