package com.coffeeorder.domain.point.service;

public record PointPaymentPreparation(boolean sufficient, PointPaymentLock paymentLock) {

    public static PointPaymentPreparation sufficient(PointPaymentLock paymentLock) {
        return new PointPaymentPreparation(true, paymentLock);
    }

    public static PointPaymentPreparation insufficient() {
        return new PointPaymentPreparation(false, null);
    }
}
