package com.antmillion.stock.support;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class IsolatedTestDataSourceTest {
    @Test
    public void allowsOnlyDedicatedSettings() {
        IsolatedTestDataSource.validateSettings(IsolatedTestDataSource.URL,
                IsolatedTestDataSource.USERNAME, "unused-validation-value");
    }

    @Test
    public void rejectsDevelopmentAndRemoteTargetsBeforePoolCreation() {
        String[] rejected = {
                null,
                "jdbc:mysql://127.0.0.1:3307/antmillion",
                "jdbc:mysql://127.0.0.1:13307/antmillion",
                "jdbc:mysql://example.invalid:13307/antmillion_test",
                "jdbc:mysql://127.0.0.1:13307,example.invalid:3306/antmillion_test",
                IsolatedTestDataSource.URL + "&socketFactory=unexpected.Factory"
        };
        for (String url : rejected) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatedTestDataSource.create(url, IsolatedTestDataSource.USERNAME,
                            "unused-validation-value"));
        }
    }

    @Test
    public void rejectsOtherUsersBeforePoolCreation() {
        for (String username : new String[] {null, "root", "antmillion_dev"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatedTestDataSource.create(IsolatedTestDataSource.URL,
                            username, "unused-validation-value"));
        }
    }

    @Test
    public void rejectsMissingCredentialsBeforePoolCreation() {
        for (String password : new String[] {null, "", " "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IsolatedTestDataSource.create(IsolatedTestDataSource.URL,
                            IsolatedTestDataSource.USERNAME, password));
        }
    }
}
