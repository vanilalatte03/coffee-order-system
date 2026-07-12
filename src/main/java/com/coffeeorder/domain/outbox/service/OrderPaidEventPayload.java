package com.coffeeorder.domain.outbox.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

@JsonPropertyOrder({
    "schemaVersion",
    "eventId",
    "eventType",
    "occurredAt",
    "orderId",
    "userId",
    "menuId",
    "paymentAmount"
})
public record OrderPaidEventPayload(
        int schemaVersion,
        String eventId,
        String eventType,
        Instant occurredAt,
        long orderId,
        long userId,
        long menuId,
        long paymentAmount) {}
