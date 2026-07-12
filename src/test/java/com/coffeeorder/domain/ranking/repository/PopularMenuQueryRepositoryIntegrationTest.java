package com.coffeeorder.domain.ranking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.MySqlIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@SpringBootTest
@DisplayName("인기 메뉴 조회 저장소 통합 테스트")
class PopularMenuQueryRepositoryIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired private NamedParameterJdbcTemplate jdbcTemplate;

    @DisplayName("인기 집계 조회는 idx_orders_popular 인덱스를 사용한다")
    @Test
    void 인기_집계_query는_idx_orders_popular_인덱스를_사용한다() {
        Instant to = Instant.parse("2026-07-10T04:40:00.123456Z");
        Map<String, Timestamp> parameters =
                Map.of(
                        "from", Timestamp.from(to.minusSeconds(168L * 60 * 60)),
                        "to", Timestamp.from(to));

        String executionPlan =
                jdbcTemplate.queryForObject(
                        "EXPLAIN FORMAT=JSON " + PopularMenuQueryRepository.POPULAR_MENUS_QUERY,
                        parameters,
                        String.class);

        assertThat(executionPlan).contains("idx_orders_popular");
    }
}
