package ru.itmo.soa.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;

/** Small JDBC configuration shared by the application and deployment checks. */
public final class JdbcDatabase {
    private final String url, user, password, schema;
    private final boolean postgres;

    private JdbcDatabase(String url, String user, String password, String schema, String driver) {
        this.url = url; this.user = user; this.password = password; this.schema = schema;
        postgres = url.startsWith("jdbc:postgresql:");
        if (!schema.isEmpty() && !schema.matches("[a-z][a-z0-9_]{0,62}"))
            throw new IllegalArgumentException("JDBC_SCHEMA must be a lowercase SQL identifier (1-63 characters)");
        try { Class.forName(driver); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("JDBC driver missing: " + driver, e); }
    }

    public static JdbcDatabase fromSettings() {
        String fallback = "jdbc:h2:file:" + Path.of(".runtime/data/workers").toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        String url = Settings.get("JDBC_URL", fallback);
        boolean postgres = url.startsWith("jdbc:postgresql:");
        String passwordFile = Settings.get("JDBC_PASSWORD_FILE", "");
        String password = Settings.get("JDBC_PASSWORD", "");
        if (!passwordFile.isEmpty()) {
            try { password = Files.readString(Path.of(passwordFile), StandardCharsets.UTF_8).replaceFirst("\\r?\\n$", ""); }
            catch (IOException e) { throw new IllegalStateException("Cannot read JDBC password file", e); }
        }
        return new JdbcDatabase(url, Settings.get("JDBC_USER", postgres ? "studs" : "sa"), password,
            Settings.get("JDBC_SCHEMA", postgres ? "soa_lab2" : ""), Settings.get("JDBC_DRIVER", postgres ? "org.postgresql.Driver" : "org.h2.Driver"));
    }

    public boolean isPostgres() { return postgres; }
    public String schema() { return schema; }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        try {
            if (!schema.isEmpty()) connection.setSchema(schema);
            return connection;
        } catch (SQLException e) { connection.close(); throw e; }
    }

    public void ensureSchema() throws SQLException {
        if (!postgres || schema.isEmpty()) return;
        try (var connection = DriverManager.getConnection(url, user, password)) {
            boolean exists;
            try (var query = connection.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname=?")) {
                query.setString(1, schema);
                try (var rows = query.executeQuery()) { exists = rows.next(); }
            }
            if (!exists) try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            }
        }
    }

    /** Transactional probe: checks DDL, DML and identity support without leaving a table. */
    public String checkAccess() throws SQLException {
        if (!postgres || schema.isEmpty()) throw new IllegalStateException("Helios requires PostgreSQL and a dedicated JDBC_SCHEMA; no H2 fallback is allowed");
        ensureSchema();
        try (var connection = open()) {
            // A successful probe must not hide missing rights on an existing installation.
            for (String table : new String[]{"organizations", "workers"}) {
                String relation = schema + "." + table;
                try (var query = connection.prepareStatement("SELECT to_regclass(?)")) {
                    query.setString(1, relation);
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        if (rows.getObject(1) == null) continue;
                    }
                }
                for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE"}) {
                    try (var query = connection.prepareStatement("SELECT has_table_privilege(?, ?)")) {
                        query.setString(1, relation); query.setString(2, privilege);
                        try (var rows = query.executeQuery()) {
                            rows.next();
                            if (!rows.getBoolean(1)) throw new SQLException("Missing table privilege", "42501");
                        }
                    }
                }
                if (table.equals("workers")) try (var query = connection.prepareStatement("SELECT has_sequence_privilege(pg_get_serial_sequence(?, 'id'), 'USAGE')")) {
                    query.setString(1, relation);
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        if (!rows.getBoolean(1)) throw new SQLException("Missing identity sequence privilege", "42501");
                    }
                }
            }
            connection.setAutoCommit(false);
            try {
                String table = "__soa_probe_" + java.util.UUID.randomUUID().toString().replace("-", "");
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE " + table + " (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, value INTEGER)");
                    statement.executeUpdate("INSERT INTO " + table + " (value) VALUES (1)");
                    statement.executeQuery("SELECT * FROM " + table).close();
                    statement.executeUpdate("UPDATE " + table + " SET value=2");
                    statement.executeUpdate("DELETE FROM " + table);
                }
                return connection.getMetaData().getDatabaseProductVersion();
            } finally { connection.rollback(); }
        }
    }
}
