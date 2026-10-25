package com.smartscity.id.autoconfigure.jdbc;

/**
 * Selects supported database clock queries and validates the configured dialect name.
 *
 * @author jack
 */
public enum DatabaseDialect {
  MYSQL,
  TIDB,
  POSTGRESQL;

  /**
   * Parses a supported dialect name without selecting a fallback for unknown values.
   *
   * @param value mysql, tidb or postgresql, ignoring case
   * @return the matching dialect
   * @throws NullPointerException if value is null
   * @throws IllegalArgumentException if the name is unsupported
   */
  public static DatabaseDialect from(String value) {
    return valueOf(value.toUpperCase(java.util.Locale.ROOT));
  }

  String timeQuery() {
    return this == POSTGRESQL
        ? "SELECT CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)"
        : "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
  }
}
