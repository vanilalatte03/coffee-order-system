package com.coffeeorder.domain.order.dto;

import java.time.Instant;

public record CreateOrderResponse(
        long orderId,
        long userId,
        OrderMenuResponse menu,
        long unitPrice,
        int quantity,
        long paidAmount,
        long remainingPointBalance,
        String status,
        Instant paidAt) {}
