package com.coffeeorder.domain.ranking.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 인기 메뉴 계약을 MySQL 원본 주문에 직접 적용하는 읽기 전용 JDBC repository.
 *
 * <p>기간 집계의 실행 계획은 수용 기준의 일부이므로, 쿼리는 {@code idx_orders_popular} 인덱스와 동일한 접근 경로를 유지하도록 강제한다.
 */
@Repository
public class PopularMenuQueryRepository {

    static final String POPULAR_MENUS_QUERY =
            """
            SELECT o.menu_id, m.name, m.price, COUNT(*) AS order_count
            FROM orders o FORCE INDEX (idx_orders_popular)
            JOIN menus m ON m.id = o.menu_id
            WHERE o.status = 'PAID'
              AND o.paid_at >= :from
              AND o.paid_at < :to
              AND m.status = 'ACTIVE'
            GROUP BY o.menu_id, m.name, m.price
            ORDER BY order_count DESC, o.menu_id ASC
            LIMIT 3
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PopularMenuQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 결제 시각의 half-open 범위에서 현재 활성인 메뉴만 최대 세 개 조회한다.
     *
     * <p>동률은 메뉴 ID 오름차순으로 안정화하고, 응답의 이름과 가격은 주문 snapshot이 아니라 현재 메뉴 카탈로그 값이다.
     */
    public List<PopularMenuQueryRow> findPopularMenus(Instant from, Instant to) {
        Map<String, Timestamp> parameters =
                Map.of("from", Timestamp.from(from), "to", Timestamp.from(to));

        return jdbcTemplate.query(
                POPULAR_MENUS_QUERY,
                parameters,
                (resultSet, rowNumber) ->
                        new PopularMenuQueryRow(
                                resultSet.getLong("menu_id"),
                                resultSet.getString("name"),
                                resultSet.getLong("price"),
                                resultSet.getLong("order_count")));
    }
}
