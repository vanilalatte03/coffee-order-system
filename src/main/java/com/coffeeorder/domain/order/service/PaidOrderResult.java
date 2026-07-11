package com.coffeeorder.domain.order.service;

import java.time.Instant;

public record PaidOrderResult(
        long orderId,
        long userId,
        long menuId,
        String menuName,
        long paymentAmount,
        Instant paidAt) {}
