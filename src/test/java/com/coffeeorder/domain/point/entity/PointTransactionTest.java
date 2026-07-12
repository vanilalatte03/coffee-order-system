package com.coffeeorder.domain.point.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("포인트 거래 엔티티")
class PointTransactionTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-11T00:00:00Z");

    @DisplayName("충전 원장은 주문 ID 없이 변경 직후 잔액을 기록한다")
    @Test
    void 충전_원장은_orderId_없이_변경_직후_잔액을_기록한다() {
        PointTransaction transaction = PointTransaction.charge(10, 500, 1500, CREATED_AT);

        assertThat(transaction.getUserId()).isEqualTo(10);
        assertThat(transaction.getOrderId()).isNull();
        assertThat(transaction.getType()).isEqualTo(PointTransactionType.CHARGE);
        assertThat(transaction.getAmount()).isEqualTo(500);
        assertThat(transaction.getBalanceAfter()).isEqualTo(1500);
    }

    @DisplayName("결제 원장은 스칼라 주문 ID와 변경 직후 잔액을 기록한다")
    @Test
    void 결제_원장은_scalar_orderId와_변경_직후_잔액을_기록한다() {
        PointTransaction transaction = PointTransaction.payment(10, 99, 700, 300, CREATED_AT);

        assertThat(transaction.getOrderId()).isEqualTo(99);
        assertThat(transaction.getType()).isEqualTo(PointTransactionType.PAYMENT);
        assertThat(transaction.getAmount()).isEqualTo(700);
        assertThat(transaction.getBalanceAfter()).isEqualTo(300);
    }

    @DisplayName("결제 원장은 유효한 주문 ID를 요구한다")
    @Test
    void 결제_원장은_유효한_orderId를_요구한다() {
        assertThatThrownBy(() -> PointTransaction.payment(10, 0, 700, 300, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
