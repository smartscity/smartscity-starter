package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Verifies bounded registration, format conflicts and shutdown against a test PostgreSQL.
 *
 * @author jack
 */
@EnabledIfSystemProperty(named = "id.test.postgres.url", matches = ".+")
class PostgresIntegrationTest {
  @Test
  void postgresIdentityClockGroupConflictAndOffline() throws Exception {
    String url = System.getProperty("id.test.postgres.url");
    assertTrue(url.contains("smartscity_id_test"));
    String sql =
        new String(
            getClass().getResourceAsStream("/db/postgresql.sql").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    try (Connection c = DriverManager.getConnection(url, "postgres", "");
        Statement s = c.createStatement()) {
      for (String part : sql.split(";")) {
        if (!part.trim().isEmpty()) {
          s.execute(
              part.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
                  .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS "));
        }
      }
    }
    IdProperties registry = new IdProperties();
    registry.getDatabase().setType("postgresql");
    registry.getDatabase().setUrl(url);
    registry.getDatabase().setUsername("postgres");
    JdbcIntegrationTest.exerciseRegistry(
        new DriverDataSource(registry.getDatabase(), 10000),
        com.smartscity.id.autoconfigure.jdbc.DatabaseDialect.POSTGRESQL);
    IdProperties p = new IdProperties();
    p.setGroup("pg_" + System.nanoTime());
    p.setBufferSize(64);
    p.getDatabase().setType("postgresql");
    p.getDatabase().setUrl(url);
    p.getDatabase().setUsername("postgres");
    long raw;
    try (IdGenerator a = new IdGeneratorFactory().create(p, null);
        IdGenerator b = new IdGeneratorFactory().create(p, null)) {
      raw = a.snapshot().getRawWorkerId();
      assertNotEquals(raw, b.snapshot().getRawWorkerId());
      assertNotEquals(a.getUniqueId(), b.getUniqueId());
      p.setProfile("high-throughput");
      assertEquals(
          IdException.Reason.CONFIGURATION,
          assertThrows(IdException.class, () -> new IdGeneratorFactory().create(p, null))
              .getReason());
    }
    try (Connection c = DriverManager.getConnection(url, "postgres", "");
        ResultSet rs =
            c.createStatement()
                .executeQuery("SELECT status FROM smartscity_id_worker WHERE id=" + raw)) {
      assertTrue(rs.next());
      assertEquals("STOPPED", rs.getString(1));
    }
  }
}
