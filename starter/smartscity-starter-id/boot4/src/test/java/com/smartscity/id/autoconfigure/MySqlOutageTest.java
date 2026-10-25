package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartscity.id.api.IdException;
import com.smartscity.id.api.IdGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Explicitly destructive ONLY to the dedicated local test server; separately opted in.
 *
 * @author jack
 */
@EnabledIfSystemProperty(named = "id.test.mysql.outage", matches = "true")
class MySqlOutageTest {
  @Test
  @Timeout(20)
  void databaseShutdownDoesNotStopAnAlreadyStartedSdk() throws Exception {
    String url = System.getProperty("id.test.mysql.url");
    assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13367/smartscity_id_test"));
    IdProperties p = new IdProperties();
    p.setGroup("outage_" + System.nanoTime());
    p.setBufferSize(64);
    p.setStartupTimeout(Duration.ofSeconds(2));
    p.setShutdownTimeout(Duration.ofMillis(300));
    p.getDatabase().setUrl(url);
    p.getDatabase().setUsername("root");
    try (IdGenerator generator = new IdGeneratorFactory().create(p, null)) {
      Set<Long> ids = new HashSet<>();
      ids.add(generator.getUniqueId());
      try (Connection c = DriverManager.getConnection(url, "root", "");
          Statement s = c.createStatement()) {
        s.execute("SHUTDOWN");
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      boolean down = false;
      while (System.nanoTime() < deadline) {
        try (Connection ignored = DriverManager.getConnection(url, "root", "")) {
          Thread.sleep(10);
        } catch (SQLException e) {
          down = true;
          break;
        }
      }
      assertTrue(down, "Isolated MySQL must actually be stopped");
      for (int i = 0; i < 256; i++) {
        assertTrue(ids.add(generator.getUniqueId()));
      }
      assertTrue(generator.snapshot().isReady());
      assertThrows(IdException.class, () -> new IdGeneratorFactory().create(p, null));
    }
  }
}
