package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.point.entity.PointWallet;

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
