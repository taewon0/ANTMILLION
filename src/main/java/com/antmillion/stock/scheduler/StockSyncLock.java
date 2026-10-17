package com.antmillion.stock.scheduler;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class StockSyncLock {
    private final DataSource dataSource;

    public StockSyncLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Handle tryAcquire() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isEmpty()) throw new SQLException("Stock sync database is not selected");
            String name = lockName(catalog);
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Stock sync lock result missing");
                    int acquired = result.getInt(1);
                    if (result.wasNull()) throw new SQLException("Stock sync lock unavailable");
                    if (acquired == 1) return new Handle(connection, name);
                }
            }
            connection.close();
            return null;
        } catch (SQLException | RuntimeException e) {
            abortAndClose(connection);
            throw e;
        }
    }

    private static String lockName(String catalog) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(catalog.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("antmillion:stock-sync:");
            for (int i = 0; i < 16; i++) name.append(String.format("%02x", hash[i] & 0xff));
            return name.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void abortAndClose(Connection connection) {
        try { connection.abort(Runnable::run); } catch (SQLException ignored) { }
        try { connection.close(); } catch (SQLException ignored) { }
    }

    public static final class Handle implements AutoCloseable {
        private final Connection connection;
        private final String name;

        private Handle(Connection connection, String name) {
            this.connection = connection;
            this.name = name;
        }

        public void assertHeld() {
            try (PreparedStatement statement = connection.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) throw new SQLException("Stock sync lock lost");
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Stock sync lock lost", e);
            }
        }

        @Override
        public void close() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) throw new SQLException("Stock sync lock release failed");
                }
            } catch (SQLException e) {
                // A pooled connection must not be returned with a session lock still held.
                abortAndClose(connection);
                throw e;
            }
            connection.close();
        }
    }
}
