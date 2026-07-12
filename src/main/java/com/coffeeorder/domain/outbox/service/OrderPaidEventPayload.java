package com.coffeeorder.domain.outbox.service;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

/**
 * 데이터 플랫폼으로 전달하는 V1 {@code ORDER_PAID} 이벤트의 고정 JSON 계약.
 *
 * <p>{@code occurredAt}은 발행 시각이 아니라 주문 결제 시각이며, 재시도에서도 바뀌지 않는다.
 */
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
