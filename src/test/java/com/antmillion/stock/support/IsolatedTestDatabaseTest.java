package com.antmillion.stock.support;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:test-context.xml")
public class IsolatedTestDatabaseTest {
    @Autowired
    private DataSource dataSource;

    @Test
    public void connectsAsDedicatedUserToMarkedSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("antmillion_test", connection.getCatalog());
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals("antmillion_test", jdbc.queryForObject(
                "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class));
        assertEquals("antmillion-isolated-test-v1", jdbc.queryForObject(
                "SELECT marker FROM test_environment", String.class));
    }

    @Test
    public void cannotChangeMarkerOrCreateTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE test_environment SET marker = marker"));
        assertThrows(DataAccessException.class,
                () -> jdbc.execute("CREATE TABLE privilege_probe (id INT)"));
    }
}
