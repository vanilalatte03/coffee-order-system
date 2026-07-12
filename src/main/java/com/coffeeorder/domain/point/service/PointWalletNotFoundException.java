package com.coffeeorder.domain.point.service;

public class PointWalletNotFoundException extends RuntimeException {

    public PointWalletNotFoundException(long userId) {
        super("point wallet not found: " + userId);
    }
}
