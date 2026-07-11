package com.coffeeorder.domain.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class PointPaymentLockIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired private PointWriteService pointWriteService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update(
                "UPDATE point_wallets SET balance = 10000, updated_at = UTC_TIMESTAMP(6)");
    }

    @Test
    void 준비와_완료를_분리한_트랜잭션에서는_detached_token을_거부한다() {
        PointPaymentPreparation preparation = pointWriteService.preparePayment(10, 1000);
        long orderId = insertPaidOrder(10);

        assertThatThrownBy(
                        () ->
                                pointWriteService.completePayment(
                                        preparation.paymentLock(), 10, orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction that acquired it");

        assertUnchanged();
    }

    @Test
    void token의_사용자와_기대_사용자가_다르면_완료를_거부한다() {
        long anotherUsersOrderId = insertPaidOrder(20);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        ignored -> {
                                            PointPaymentPreparation preparation =
                                                    pointWriteService.preparePayment(10, 1000);
                                            pointWriteService.completePayment(
                                                    preparation.paymentLock(),
                                                    20,
                                                    anotherUsersOrderId);
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another user");

        assertUnchanged();
    }

    @Test
    void 같은_token을_두_번_완료하면_전체_트랜잭션을_롤백한다() {
        long firstOrderId = insertPaidOrder(10);
        long secondOrderId = insertPaidOrder(10);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        ignored -> {
                                            PointPaymentPreparation preparation =
                                                    pointWriteService.preparePayment(10, 1000);
                                            pointWriteService.completePayment(
                                                    preparation.paymentLock(), 10, firstOrderId);
                                            pointWriteService.completePayment(
                                                    preparation.paymentLock(), 10, secondOrderId);
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");

        assertUnchanged();
    }

    private long insertPaidOrder(long userId) {
        jdbcTemplate.update(
                """
                INSERT INTO orders
                    (user_id, menu_id, menu_name_snapshot, unit_price, quantity, paid_amount,
                     status, paid_at, created_at)
                VALUES (?, 1, '아메리카노', 4500, 1, 4500, 'PAID',
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """,
                userId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM orders", Long.class);
    }

    private void assertUnchanged() {
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT balance FROM point_wallets WHERE user_id = 10", Long.class))
                .isEqualTo(10000);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM point_transactions", Integer.class))
                .isZero();
    }
}
