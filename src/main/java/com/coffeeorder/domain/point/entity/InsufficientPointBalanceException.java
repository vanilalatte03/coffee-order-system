package com.coffeeorder.domain.point.entity;

public class InsufficientPointBalanceException extends RuntimeException {

    public InsufficientPointBalanceException(long balance, long amount) {
        super("insufficient point balance: balance=" + balance + ", amount=" + amount);
    }
}
