package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.point.entity.PointWallet;

/**
 * 잠긴 지갑 행을 같은 주문 트랜잭션에서 한 번만 결제에 사용할 수 있게 하는 내부 권한 객체.
 *
 * <p>DB 잠금 자체를 표현하는 값은 아니다. 이 객체가 가리키는 영속 엔티티와 트랜잭션이 유지될 때만 유효하며, 다른 스레드나 트랜잭션으로 전달해서는 안 된다.
 */
public final class PointPaymentLock {

    private final PointWallet wallet;
    private final long amount;
    private boolean consumed;

    PointPaymentLock(PointWallet wallet, long amount) {
        this.wallet = wallet;
        this.amount = amount;
    }

    long userId() {
        return wallet.getUserId();
    }

    PointWallet wallet() {
        return wallet;
    }

    long amount() {
        return amount;
    }

    PointWallet consume(long expectedUserId) {
        if (consumed) {
            throw new IllegalStateException("payment lock is already consumed");
        }
        if (userId() != expectedUserId) {
            throw new IllegalArgumentException("payment lock belongs to another user");
        }
        consumed = true;
        return wallet;
    }
}
