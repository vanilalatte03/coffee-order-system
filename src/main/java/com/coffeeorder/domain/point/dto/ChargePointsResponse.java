package com.coffeeorder.domain.point.dto;

import com.coffeeorder.domain.point.service.PointChargeResult;
import java.time.Instant;

public record ChargePointsResponse(
        long pointTransactionId, long userId, long chargedAmount, long balance, Instant chargedAt) {

    public static ChargePointsResponse from(
            long userId, long chargedAmount, PointChargeResult result) {
        return new ChargePointsResponse(
                result.pointTransactionId(),
                userId,
                chargedAmount,
                result.balance(),
                result.chargedAt());
    }
}
