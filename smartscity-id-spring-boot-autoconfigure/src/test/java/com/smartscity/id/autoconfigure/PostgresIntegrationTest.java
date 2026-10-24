package com.smartscity.id.autoconfigure;

import com.smartscity.id.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "id.test.postgres.url", matches = ".+")
class PostgresIntegrationTest {
    @Test void postgresIdentityClockGroupConflictAndOffline() throws Exception {
        String url = System.getProperty("id.test.postgres.url");
        assertTrue(url.contains("smartscity_id_test"));
        String sql = new String(getClass().getResourceAsStream("/db/postgresql.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, "postgres", ""); Statement s = c.createStatement()) {
            for (String part : sql.split(";")) if (!part.trim().isEmpty()) s.execute(part.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ").replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS "));
        }
        IdProperties p = new IdProperties(); p.setGroup("pg_" + System.nanoTime()); p.setBufferSize(64);
        p.getDatabase().setType("postgresql"); p.getDatabase().setUrl(url); p.getDatabase().setUsername("postgres");
        long raw;
        try (IdGenerator a = new IdGeneratorFactory().create(p, null);
             IdGenerator b = new IdGeneratorFactory().create(p, null)) {
            raw = a.snapshot().getRawWorkerId(); assertNotEquals(raw, b.snapshot().getRawWorkerId());
            assertNotEquals(a.getUniqueId(), b.getUniqueId());
            p.setProfile("high-throughput");
            assertEquals(IdException.Reason.CONFIGURATION, assertThrows(IdException.class,
                    () -> new IdGeneratorFactory().create(p, null)).getReason());
        }
        try (Connection c = DriverManager.getConnection(url, "postgres", "");
             ResultSet rs = c.createStatement().executeQuery("SELECT state FROM smartscity_id_worker WHERE id=" + raw)) {
            assertTrue(rs.next()); assertEquals("OFFLINE", rs.getString(1));
        }
    }
}
