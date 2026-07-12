package com.coffeeorder;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class EnabledOutboxIntegrationTestSupport {

    @DynamicPropertySource
    static void configureMySqlAndOutbox(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EnabledOutboxIntegrationTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", MySqlIntegrationTestSupport.mysql::getUsername);
        registry.add("spring.datasource.password", MySqlIntegrationTestSupport.mysql::getPassword);
        registry.add("outbox.delivery.enabled", () -> "true");
    }

    private static String jdbcUrl() {
        String url = MySqlIntegrationTestSupport.mysql.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
