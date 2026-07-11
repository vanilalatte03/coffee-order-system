package com.coffeeorder.domain.point.entity;

public class InvalidPointAmountException extends IllegalArgumentException {

    public InvalidPointAmountException(long amount) {
        super("point amount must be positive: " + amount);
    }
}
