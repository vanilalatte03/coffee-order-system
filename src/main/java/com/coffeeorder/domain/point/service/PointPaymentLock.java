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

    long amount() {
        return amount;
    }

    PointWallet consume() {
        if (consumed) {
            throw new IllegalStateException("payment lock is already consumed");
        }
        consumed = true;
        return wallet;
    }
}
