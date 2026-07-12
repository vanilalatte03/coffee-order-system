package com.coffeeorder.domain.point.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("포인트 지갑 엔티티")
class PointWalletTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-11T00:00:00Z");
    private static final Instant CHANGED_AT = Instant.parse("2026-07-11T00:01:00Z");

    @DisplayName("1포인트와 일반 금액을 충전한다")
    @Test
    void 일포인트와_일반_금액을_충전한다() {
        PointWallet wallet = wallet(0);

        assertThat(wallet.charge(1, CHANGED_AT)).isEqualTo(1);
        assertThat(wallet.charge(999, CHANGED_AT)).isEqualTo(1000);
        assertThat(wallet.getBalance()).isEqualTo(1000);
    }

    @DisplayName("0과 음수 충전을 거절한다")
    @Test
    void 영과_음수_충전을_거절한다() {
        PointWallet wallet = wallet(100);

        assertThatThrownBy(() -> wallet.charge(0, CHANGED_AT))
                .isInstanceOf(InvalidPointAmountException.class);
        assertThatThrownBy(() -> wallet.charge(-1, CHANGED_AT))
                .isInstanceOf(InvalidPointAmountException.class);
        assertThat(wallet.getBalance()).isEqualTo(100);
    }

    @DisplayName("long 범위 초과 충전을 부분 변경 없이 거절한다")
    @Test
    void long_범위_초과_충전을_부분_변경_없이_거절한다() {
        PointWallet wallet = wallet(Long.MAX_VALUE);

        assertThatThrownBy(() -> wallet.charge(1, CHANGED_AT))
                .isInstanceOf(PointBalanceOverflowException.class);
        assertThat(wallet.getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @DisplayName("충분한 잔액을 결제한다")
    @Test
    void 충분한_잔액을_결제한다() {
        PointWallet wallet = wallet(1000);

        assertThat(wallet.pay(700, CHANGED_AT)).isEqualTo(300);
        assertThat(wallet.getBalance()).isEqualTo(300);
    }

    @DisplayName("부족한 잔액의 결제를 부분 변경 없이 거절한다")
    @Test
    void 부족한_잔액의_결제를_부분_변경_없이_거절한다() {
        PointWallet wallet = wallet(999);

        assertThatThrownBy(() -> wallet.pay(1000, CHANGED_AT))
                .isInstanceOf(InsufficientPointBalanceException.class);
        assertThat(wallet.getBalance()).isEqualTo(999);
    }

    private static PointWallet wallet(long balance) {
        return new PointWallet(10, balance, CREATED_AT, CREATED_AT);
    }
}
