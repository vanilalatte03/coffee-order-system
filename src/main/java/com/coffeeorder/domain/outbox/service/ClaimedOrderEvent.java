package com.coffeeorder.domain.outbox.service;

import java.time.Instant;

public record ClaimedOrderEvent(
        String eventId, String payload, int attemptCount, String claimToken, Instant createdAt) {

    public ClaimedOrderEvent(String eventId, String payload, int attemptCount, String claimToken) {
        this(eventId, payload, attemptCount, claimToken, Instant.EPOCH);
    }
}
