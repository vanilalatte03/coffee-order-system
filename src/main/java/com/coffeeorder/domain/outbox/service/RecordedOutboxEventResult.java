package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import java.time.Instant;

public record RecordedOutboxEventResult(
        String eventId,
        String payload,
        OutboxStatus status,
        int attemptCount,
        Instant nextAttemptAt) {}
