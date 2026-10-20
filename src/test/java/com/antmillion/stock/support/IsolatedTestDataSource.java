package com.antmillion.stock.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 일회성 Compose 테스트 DB만 허용하며 db.properties는 읽지 않습니다. */
public final class IsolatedTestDataSource {
    static final String URL = "jdbc:mysql://127.0.0.1:13307/antmillion_test"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul";
    static final String USERNAME = "antmillion_test";

    private IsolatedTestDataSource() {
    }

    public static HikariDataSource create() throws SQLException {
        return create(System.getProperty("test.db.url", URL),
                System.getProperty("test.db.username", USERNAME),
                System.getenv("ANTMILLION_TEST_DB_PASSWORD"));
    }

    static HikariDataSource create(String url, String username, String password) throws SQLException {
        // 잘못된 설정으로 연결이 열리지 않도록 풀을 생성하기 전에 검증합니다.
        validateSettings(url, username, password);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(5000);
        config.setPoolName("isolated-trading-test");
        HikariDataSource dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT marker FROM test_environment")) {
            if (!result.next() || !"antmillion-isolated-test-v1".equals(result.getString(1))
                    || result.next()) {
                throw new SQLException("Isolated test database marker is missing or invalid");
            }
        } catch (SQLException | RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return dataSource;
    }

    static void validateSettings(String url, String username, String password) {
        if (!URL.equals(url) || !USERNAME.equals(username)) {
            throw new IllegalArgumentException("Only the disposable trading test database is allowed");
        }
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Run scripts/Test-Trading.ps1 to supply test credentials");
        }
    }
}
