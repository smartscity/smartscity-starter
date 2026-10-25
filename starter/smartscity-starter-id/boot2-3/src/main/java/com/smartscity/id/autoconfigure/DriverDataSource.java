package com.smartscity.id.autoconfigure;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Unpooled startup/shutdown connections: no SDK housekeeping or idle database traffic.
 *
 * @author jack
 */
final class DriverDataSource implements DataSource {
  private final String url;
  private final Properties properties = new Properties();

  DriverDataSource(IdProperties.Database config, long timeoutMillis) throws ClassNotFoundException {
    url = config.getUrl();
    if (config.getDriverClassName() != null) {
      Class.forName(config.getDriverClassName());
    }
    if (config.getUsername() != null) {
      properties.setProperty("user", config.getUsername());
    }
    if (config.getPassword() != null) {
      properties.setProperty("password", config.getPassword());
    }
    boolean postgres = config.getType().equalsIgnoreCase("postgresql");
    String timeout =
        Long.toString(postgres ? Math.max(1, (timeoutMillis + 999) / 1000) : timeoutMillis);
    properties.setProperty("connectTimeout", timeout);
    properties.setProperty("socketTimeout", timeout);
  }

  public Connection getConnection() throws SQLException {
    return DriverManager.getConnection(url, properties);
  }

  public Connection getConnection(String username, String password) throws SQLException {
    Properties copy = new Properties();
    copy.putAll(properties);
    copy.setProperty("user", username);
    copy.setProperty("password", password);
    return DriverManager.getConnection(url, copy);
  }

  public PrintWriter getLogWriter() {
    return null;
  }

  public void setLogWriter(PrintWriter writer) {
    throw new UnsupportedOperationException();
  }

  public int getLoginTimeout() {
    return 0;
  }

  public void setLoginTimeout(int seconds) {
    throw new UnsupportedOperationException("Configure startup-timeout");
  }

  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  public <T> T unwrap(Class<T> type) throws SQLException {
    if (type.isInstance(this)) {
      return type.cast(this);
    }
    throw new SQLException("Not a wrapper for " + type);
  }

  public boolean isWrapperFor(Class<?> type) {
    return type.isInstance(this);
  }
}
