package com.coffeeorder.domain.ranking.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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
