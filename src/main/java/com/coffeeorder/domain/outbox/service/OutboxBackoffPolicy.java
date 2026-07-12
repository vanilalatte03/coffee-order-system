package com.coffeeorder.domain.outbox.service;

import java.time.Duration;

/**
 * 재시도 가능한 Outbox 발행 실패의 지수 백오프 정책.
 *
 * <p>n번째 재시도 지연은 {@code min(2^(n-1)초 × jitter, 300초)}이며, jitter는 0.8부터 1.2까지다.
 */
public final class OutboxBackoffPolicy {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MAX_DELAY_MICROS = 300L * MICROS_PER_SECOND;

    /**
     * 방금 끝난 선점 횟수에 대응하는 다음 재시도 지연을 계산한다.
     *
     * <p>선점 횟수 1은 최초 전달 실패 뒤의 첫 재시도 예약이고, 10은 마지막 재시도 예약이다.
     */
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
