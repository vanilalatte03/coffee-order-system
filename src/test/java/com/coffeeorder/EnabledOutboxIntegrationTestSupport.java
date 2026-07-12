package com.coffeeorder;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Outbox scheduler·after-commit wiring을 검증할 때 전달 기능을 명시적으로 켜는 통합 테스트 기반 클래스.
 *
 * <p>MySQL 컨테이너와 UTC JDBC 설정은 {@link MySqlIntegrationTestSupport}와 공유한다.
 */
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
