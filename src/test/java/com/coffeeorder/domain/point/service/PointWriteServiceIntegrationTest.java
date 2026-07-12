package com.coffeeorder.domain.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.point.entity.InsufficientPointBalanceException;
import com.coffeeorder.domain.point.entity.PointBalanceOverflowException;
import com.coffeeorder.domain.user.service.UserNotFoundException;
import com.coffeeorder.domain.user.service.UserService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("포인트 쓰기 서비스 통합 테스트")
class PointWriteServiceIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired private PointWriteService pointWriteService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetPointData() {
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 0, updated_at = UTC_TIMESTAMP(6)");
    }

    @DisplayName("1포인트 충전은 잔액과 충전 원장을 함께 기록한다")
    @Test
    void 일포인트_충전은_잔액과_CHARGE_원장을_함께_기록한다() {
        assertThat(pointWriteService.charge(10, 1)).isEqualTo(1);

        assertThat(balanceOf(10)).isEqualTo(1);
        assertThat(singleLedger())
                .containsEntry("user_id", 10L)
                .containsEntry("order_id", null)
                .containsEntry("type", "CHARGE")
                .containsEntry("amount", 1L)
                .containsEntry("balance_after", 1L);
    }

    @DisplayName("일반 충전은 기존 잔액에 더한 직후 잔액을 원장에 기록한다")
    @Test
    void 일반_충전은_기존_잔액에_더한_직후_잔액을_원장에_기록한다() {
        setBalance(10, 500);

        assertThat(pointWriteService.charge(10, 1500)).isEqualTo(2000);

        assertThat(balanceOf(10)).isEqualTo(2000);
        assertThat(singleLedger())
                .containsEntry("type", "CHARGE")
                .containsEntry("amount", 1500L)
                .containsEntry("balance_after", 2000L);
    }

    @DisplayName("long 오버플로는 지갑과 원장을 부분 변경하지 않는다")
    @Test
    void long_오버플로는_지갑과_원장을_부분_변경하지_않는다() {
        setBalance(10, Long.MAX_VALUE);

        assertThatThrownBy(() -> pointWriteService.charge(10, 1))
                .isInstanceOf(PointBalanceOverflowException.class);

        assertThat(balanceOf(10)).isEqualTo(Long.MAX_VALUE);
        assertThat(ledgerCount()).isZero();
    }

    @DisplayName("충분한 잔액은 결제 원장 주문 ID와 변경 직후 잔액을 기록한다")
    @Test
    void 충분한_잔액은_PAYMENT_orderId와_변경_직후_잔액을_기록한다() {
        setBalance(10, 1000);
        long orderId = insertPaidOrder(10);

        assertThat(pointWriteService.pay(10, orderId, 700)).isEqualTo(300);

        assertThat(balanceOf(10)).isEqualTo(300);
        assertThat(singleLedger())
                .containsEntry("user_id", 10L)
                .containsEntry("order_id", orderId)
                .containsEntry("type", "PAYMENT")
                .containsEntry("amount", 700L)
                .containsEntry("balance_after", 300L);
    }

    @DisplayName("부족한 잔액은 지갑과 원장을 부분 변경하지 않는다")
    @Test
    void 부족한_잔액은_지갑과_원장을_부분_변경하지_않는다() {
        setBalance(10, 699);
        long orderId = insertPaidOrder(10);

        assertThatThrownBy(() -> pointWriteService.pay(10, orderId, 700))
                .isInstanceOf(InsufficientPointBalanceException.class);

        assertThat(balanceOf(10)).isEqualTo(699);
        assertThat(ledgerCount()).isZero();
    }

    @DisplayName("사용자 기능은 존재하는 사용자와 없는 사용자를 구분한다")
    @Test
    void 사용자_기능은_존재하는_사용자와_없는_사용자를_구분한다() {
        userService.validateExists(10);

        assertThatThrownBy(() -> userService.validateExists(9999))
                .isInstanceOf(UserNotFoundException.class);
    }

    @DisplayName("DB는 충전 원장의 주문 ID를 거절한다")
    @Test
    void DB는_CHARGE의_orderId를_거절한다() {
        long orderId = insertPaidOrder(10);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO point_transactions
                                            (user_id, order_id, type, amount, balance_after, created_at)
                                        VALUES (?, ?, 'CHARGE', 100, 100, UTC_TIMESTAMP(6))
                                        """,
                                        10,
                                        orderId))
                .isInstanceOf(DataAccessException.class);
        assertThat(ledgerCount()).isZero();
    }

    @DisplayName("DB는 결제 원장의 null 주문 ID를 거절한다")
    @Test
    void DB는_PAYMENT의_null_orderId를_거절한다() {
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO point_transactions
                                            (user_id, order_id, type, amount, balance_after, created_at)
                                        VALUES (10, NULL, 'PAYMENT', 100, 0, UTC_TIMESTAMP(6))
                                        """))
                .isInstanceOf(DataAccessException.class);
        assertThat(ledgerCount()).isZero();
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

    private void setBalance(long userId, long balance) {
        jdbcTemplate.update(
                "UPDATE point_wallets SET balance = ?, updated_at = UTC_TIMESTAMP(6) WHERE user_id = ?",
                balance,
                userId);
    }

    private long balanceOf(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM point_wallets WHERE user_id = ?", Long.class, userId);
    }

    private int ledgerCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_transactions", Integer.class);
    }

    private Map<String, Object> singleLedger() {
        return jdbcTemplate.queryForMap(
                """
                SELECT user_id, order_id, type, amount, balance_after
                FROM point_transactions
                """);
    }
}
