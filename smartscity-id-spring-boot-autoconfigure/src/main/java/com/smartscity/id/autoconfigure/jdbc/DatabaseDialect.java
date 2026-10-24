package com.smartscity.id.autoconfigure.jdbc;

public enum DatabaseDialect {
    MYSQL, TIDB, POSTGRESQL;
    public static DatabaseDialect from(String value) { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
    String insertGroup() {
        String base = "INSERT INTO smartscity_id_group (group_name, layout, epoch_second) VALUES (?, ?, ?)";
        return this == POSTGRESQL ? base + " ON CONFLICT (group_name) DO NOTHING"
                : base + " ON DUPLICATE KEY UPDATE group_name=group_name";
    }
    String timeQuery() {
        return this == POSTGRESQL
                ? "SELECT CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)"
                : "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
    }
}
