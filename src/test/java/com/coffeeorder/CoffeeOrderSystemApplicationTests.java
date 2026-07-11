package com.coffeeorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CoffeeOrderSystemApplicationTests extends MySqlIntegrationTestSupport {

    private static final Set<String> PHASE_ONE_TABLES =
            Set.of(
                    "users",
                    "menus",
                    "point_wallets",
                    "point_transactions",
                    "orders",
                    "idempotency_requests",
                    "outbox_events");

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {}

    @Test
    void flywayCreatesAllPhaseOneTablesConstraintsAndIndexes() {
        List<String> tables =
                jdbcTemplate.queryForList(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name IN (?, ?, ?, ?, ?, ?, ?)
                        """,
                        String.class,
                        PHASE_ONE_TABLES.toArray());

        assertThat(tables).containsExactlyInAnyOrderElementsOf(PHASE_ONE_TABLES);
        assertThat(constraintNames())
                .contains(
                        "chk_menus_price_positive",
                        "chk_point_wallets_balance_nonnegative",
                        "uk_point_transactions_order_id",
                        "uk_idempotency_scope",
                        "uk_outbox_aggregate_event",
                        "chk_outbox_attempt_count");
        assertThat(indexNames())
                .contains(
                        "idx_menus_status_id",
                        "idx_point_transactions_user_created",
                        "idx_orders_popular",
                        "idx_orders_user_paid",
                        "idx_outbox_pending",
                        "idx_outbox_expired_lease",
                        "idx_outbox_aggregate");
    }

    @Test
    void seedCreatesOneZeroBalanceWalletPerUserAndBothMenuStatuses() {
        Integer users = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        Integer wallets =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM point_wallets WHERE balance = 0", Integer.class);
        List<String> menuStatuses =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT status FROM menus ORDER BY status", String.class);

        assertThat(users).isPositive();
        assertThat(wallets).isEqualTo(users);
        assertThat(menuStatuses).containsExactly("ACTIVE", "INACTIVE");
    }

    @Test
    void mysqlEnforcesCoreCheckConstraints() {
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO menus
                                            (id, name, price, status, created_at, updated_at)
                                        VALUES (999, 'invalid', 0, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                                        """))
                .isInstanceOf(DataAccessException.class);

        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at) VALUES (999, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        try {
            assertThatThrownBy(
                            () ->
                                    jdbcTemplate.update(
                                            """
                                            INSERT INTO point_wallets
                                                (user_id, balance, created_at, updated_at)
                                            VALUES (999, -1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                                            """))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = 999");
        }
    }

    @Test
    void jdbcSessionUsesUtc() {
        String sessionTimeZone =
                jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class);

        assertThat(sessionTimeZone).isEqualTo("+00:00");
    }

    private List<String> constraintNames() {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT constraint_name
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                """,
                String.class);
    }

    private List<String> indexNames() {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                """,
                String.class);
    }
}
