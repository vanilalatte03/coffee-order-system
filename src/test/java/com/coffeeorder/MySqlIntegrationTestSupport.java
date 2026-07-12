package com.coffeeorder;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class MySqlIntegrationTestSupport {

    static final String MYSQL_IMAGE = "mysql:8.0.42";

    protected static final MySQLContainer<?> mysql =
            new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
                    .withDatabaseName("coffee_order")
                    .withUsername("coffee")
                    .withPassword("coffee");

    static {
        mysql.start();
    }

    @DynamicPropertySource
    static void configureMySql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MySqlIntegrationTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("outbox.delivery.enabled", () -> "false");
    }

    private static String jdbcUrl() {
        String separator = mysql.getJdbcUrl().contains("?") ? "&" : "?";
        return mysql.getJdbcUrl()
                + separator
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
