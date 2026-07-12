package com.coffeeorder.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("데이터베이스 실행 계획 수용 테스트")
class DatabaseExecutionPlanAcceptanceTest extends MySqlIntegrationTestSupport {

    private static final Instant TO = Instant.parse("2026-07-12T00:00:00Z");
    private static final Instant FROM = TO.minusSeconds(168L * 60 * 60);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void prepareFixedFixture() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update("UPDATE menus SET status = 'ACTIVE' WHERE id IN (1, 2, 3)");

        for (int index = 0; index < 24; index++) {
            long menuId = index % 3 + 1;
            Instant paidAt = FROM.plusSeconds(index * 60L * 60L);
            insertPaidOrder(menuId, paidAt);
        }
        for (int index = 0; index < 12; index++) {
            insertOutboxFixture(index);
        }
    }

    @DisplayName("주요 조회 인덱스의 컬럼과 순서가 정본과 일치한다")
    @Test
    void 주요_query_인덱스의_컬럼과_순서가_정본과_일치한다() {
        assertThat(indexColumns("orders", "idx_orders_popular"))
                .containsExactly("status", "paid_at", "menu_id");
        assertThat(indexColumns("outbox_events", "idx_outbox_pending"))
                .containsExactly("status", "next_attempt_at", "created_at");
        assertThat(indexColumns("outbox_events", "idx_outbox_expired_lease"))
                .containsExactly("status", "locked_until", "created_at");
    }

    @DisplayName("ANALYZE 후 운영 조회 조건의 possible_keys에 예상 인덱스가 포함된다")
    @Test
    void analyze_후_production_query조건의_possible_keys에_예상_인덱스가_포함된다() throws Exception {
        jdbcTemplate.execute("ANALYZE TABLE orders");
        jdbcTemplate.execute("ANALYZE TABLE outbox_events");

        String popularPlan =
                explain(
                        """
                        SELECT o.menu_id, m.name, m.price, COUNT(*) AS order_count
                        FROM orders o FORCE INDEX (idx_orders_popular)
                        JOIN menus m ON m.id = o.menu_id
                        WHERE o.status = 'PAID'
                          AND o.paid_at >= ?
                          AND o.paid_at < ?
                          AND m.status = 'ACTIVE'
                        GROUP BY o.menu_id, m.name, m.price
                        ORDER BY order_count DESC, o.menu_id ASC
                        LIMIT 3
                        """,
                        Timestamp.from(FROM),
                        Timestamp.from(TO));
        String pendingPlan =
                explain(
                        """
                        SELECT event_id, status, next_attempt_at AS eligible_at, created_at
                        FROM outbox_events
                        WHERE status = 'PENDING'
                          AND attempt_count < ?
                          AND next_attempt_at <= ?
                        """,
                        11,
                        Timestamp.from(TO));
        String expiredLeasePlan =
                explain(
                        """
                        SELECT event_id, status, locked_until AS eligible_at, created_at
                        FROM outbox_events
                        WHERE status = 'PROCESSING'
                          AND locked_until < ?
                        """,
                        Timestamp.from(TO));

        assertThat(possibleKeys(popularPlan)).contains("idx_orders_popular");
        assertThat(possibleKeys(pendingPlan)).contains("idx_outbox_pending");
        assertThat(possibleKeys(expiredLeasePlan)).contains("idx_outbox_expired_lease");
    }

    private List<String> indexColumns(String tableName, String indexName) {
        return jdbcTemplate.queryForList(
                """
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                ORDER BY SEQ_IN_INDEX
                """,
                String.class,
                tableName,
                indexName);
    }

    private String explain(String query, Object... arguments) {
        return jdbcTemplate.queryForObject("EXPLAIN FORMAT=JSON " + query, String.class, arguments);
    }

    private List<String> possibleKeys(String executionPlan) throws Exception {
        List<String> keys = new ArrayList<>();
        collectPossibleKeys(objectMapper.readTree(executionPlan), keys);
        return keys;
    }

    private void collectPossibleKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            node.properties()
                    .forEach(
                            entry -> {
                                if (entry.getKey().equals("possible_keys")
                                        && entry.getValue().isArray()) {
                                    entry.getValue().forEach(value -> keys.add(value.asText()));
                                }
                                collectPossibleKeys(entry.getValue(), keys);
                            });
        } else if (node.isArray()) {
            node.forEach(child -> collectPossibleKeys(child, keys));
        }
    }

    private void insertPaidOrder(long menuId, Instant paidAt) {
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    user_id, menu_id, menu_name_snapshot, unit_price, quantity,
                    paid_amount, status, paid_at, created_at)
                SELECT 10, id, name, price, 1, price, 'PAID', ?, ?
                FROM menus WHERE id = ?
                """,
                Timestamp.from(paidAt),
                Timestamp.from(paidAt),
                menuId);
    }

    private void insertOutboxFixture(int index) {
        boolean pending = index % 2 == 0;
        Instant createdAt = TO.minusSeconds(index * 60L);
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    status, attempt_count, next_attempt_at, claim_token, locked_by, locked_until,
                    created_at, updated_at)
                VALUES (?, 'ORDER', ?, 'ORDER_PAID', 1, '{}', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                10_000L + index,
                pending ? "PENDING" : "PROCESSING",
                pending ? 0 : 1,
                Timestamp.from(pending ? TO.minusSeconds(1) : TO.plusSeconds(60)),
                pending ? null : UUID.randomUUID().toString(),
                pending ? null : "fixture-worker",
                pending ? null : Timestamp.from(TO.minusSeconds(1)),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }
}
