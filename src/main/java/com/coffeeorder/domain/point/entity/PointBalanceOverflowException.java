package com.coffeeorder.domain.point.entity;

public class PointBalanceOverflowException extends RuntimeException {

    public PointBalanceOverflowException(ArithmeticException cause) {
        super("point balance overflow", cause);
    }
}
