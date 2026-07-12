package com.coffeeorder.domain.point.service;

/**
 * 지갑을 잠근 뒤 주문 결제를 계속할 수 있는지 나타내는 준비 결과.
 *
 * <p>잔액이 충분할 때만 {@code paymentLock}이 존재하며, 호출자는 이를 같은 트랜잭션에서 한 번 소비해야 한다. 부족한 경우에는 지갑·원장을 변경하지 않는다.
 */
public record PointPaymentPreparation(boolean sufficient, PointPaymentLock paymentLock) {

    public static PointPaymentPreparation sufficient(PointPaymentLock paymentLock) {
        return new PointPaymentPreparation(true, paymentLock);
    }

    public static PointPaymentPreparation insufficient() {
        return new PointPaymentPreparation(false, null);
    }
}
