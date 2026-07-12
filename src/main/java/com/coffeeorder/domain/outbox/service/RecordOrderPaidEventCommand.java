package com.coffeeorder.domain.outbox.service;

import java.time.Instant;

public record RecordOrderPaidEventCommand(
        long orderId, long userId, long menuId, long paymentAmount, Instant occurredAt) {}
