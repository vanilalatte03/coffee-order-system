package com.coffeeorder.domain.outbox.service;

public record ClaimedOrderEvent(
        String eventId, String payload, int attemptCount, String claimToken) {}
