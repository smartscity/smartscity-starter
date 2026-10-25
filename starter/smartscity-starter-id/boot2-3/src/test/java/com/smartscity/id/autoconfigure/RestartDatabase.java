package com.smartscity.id.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;

/**
 * Test-only JDBC observation; never opens an application-configured database.
 *
 * @author jack
 */
final class RestartDatabase {
  final String url = System.getProperty("id.test.mysql.url", "");
  final AtomicInteger active = new AtomicInteger();
  final AtomicInteger opened = new AtomicInteger();

  final DataSource tracked;

  RestartDatabase() throws Exception {
    assertTrue(
        url.matches("jdbc:mysql://127\\.0\\.0\\.1:13367/smartscity_id_test(?:\\?.*)?"),
        "Restart tests require the dedicated loopback test database");
    IdProperties properties = config("setup");
    DataSource delegate = new DriverDataSource(properties.getDatabase(), 5000);
    tracked =
        proxy(
            DataSource.class,
            (method, args) -> {
              Object value = invoke(delegate, method, args);
              if (!(value instanceof Connection)) {
                return value;
              }
              Connection connection = (Connection) value;
              opened.incrementAndGet();
              active.incrementAndGet();
              AtomicBoolean closed = new AtomicBoolean();
              return proxy(
                  Connection.class,
                  (cm, ca) -> {
                    Object result = invoke(connection, cm, ca);
                    if (cm.getName().equals("close") && closed.compareAndSet(false, true)) {
                      active.decrementAndGet();
                    }
                    return result;
                  });
            });
    String ddl;
    try (java.io.InputStream input = getClass().getResourceAsStream("/db/mysql.sql")) {
      ddl = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    try (Connection connection = tracked.getConnection();
        Statement statement = connection.createStatement()) {
      for (String part : ddl.split(";")) {
        if (!part.trim().isEmpty()) {
          statement.execute(part.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS "));
        }
      }
    }
  }

  IdProperties config(String group) {
    IdProperties properties = new IdProperties();
    properties.setGroup(group);
    properties.setBufferSize(32);
    properties.getDatabase().setUrl(url);
    properties.getDatabase().setUsername("root");
    return properties;
  }

  IdProperties suppliedConfig(String group) {
    IdProperties properties = config(group);
    properties.getDatabase().setUrl(null);
    return properties;
  }

  String state(long raw) throws Exception {
    try (Connection connection = tracked.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT status FROM smartscity_id_worker WHERE id=?")) {
      statement.setLong(1, raw);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getString(1);
      }
    }
  }

  int rows(String group, String state) throws Exception {
    try (Connection connection = tracked.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM smartscity_id_worker w JOIN smartscity_id_group g ON g.id=w.group_id WHERE g.group_name=? AND w.status=?")) {
      statement.setString(1, group);
      statement.setString(2, state);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getInt(1);
      }
    }
  }

  static void await(BooleanSupplier condition, int seconds, String message) throws Exception {
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), message);
  }

  static void noSdkThreads() throws Exception {
    await(
        () ->
            Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("smartscity-id-")),
        3,
        "SDK thread leaked");
  }

  private interface Call {
    Object invoke(Method method, Object[] arguments) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Call call) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> call.invoke(method, args)));
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
