package com.coffeeorder;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL 고유 동작을 검증하는 통합 테스트의 공유 Testcontainers 기반 클래스.
 *
 * <p>테스트 클래스 전체가 같은 MySQL 8.0 컨테이너를 재사용하고 JDBC 세션 시간대를 UTC로 고정한다. 기본적으로 Outbox 전달은 꺼서 외부 HTTP 호출이
 * 테스트 결과에 섞이지 않게 한다.
 */
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
