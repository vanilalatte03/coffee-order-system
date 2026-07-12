package com.coffeeorder.domain.outbox.service;

import java.time.Duration;

public final class OutboxBackoffPolicy {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MAX_DELAY_MICROS = 300L * MICROS_PER_SECOND;

    public Duration retryDelay(int attemptCount, double jitterFactor) {
        if (attemptCount < 1 || attemptCount > 10) {
            throw new IllegalArgumentException("retry attempt count must be between 1 and 10");
        }
        if (!Double.isFinite(jitterFactor) || jitterFactor < 0.8 || jitterFactor > 1.2) {
            throw new IllegalArgumentException("jitter factor must be between 0.8 and 1.2");
        }
        long baseSeconds = 1L << (attemptCount - 1);
        long delayedMicros = Math.round(baseSeconds * jitterFactor * MICROS_PER_SECOND);
        return Duration.ofNanos(Math.min(delayedMicros, MAX_DELAY_MICROS) * 1_000L);
    }
}
