package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Initializes the explicitly selected test schema independently of test-class execution order. The
 * caller owns the isolated server; this fixture owns and closes only its borrowed connection.
 *
 * @author jack
 */
final class MySqlTestSchema {
  private MySqlTestSchema() {}

  static void initialize(String url) throws Exception {
    assertTrue(
        url.matches(
            "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/smartscity_id_test(?:\\?.*)?"),
        "Use the explicit loopback test database");
    try (InputStream input = MySqlTestSchema.class.getResourceAsStream("/db/mysql.sql");
        Connection connection = DriverManager.getConnection(url, "root", "");
        Statement statement = connection.createStatement()) {
      String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      for (String part : ddl.split(";")) {
        if (!part.trim().isEmpty()) {
          statement.execute(part.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS "));
        }
      }
    }
  }
}
