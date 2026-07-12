package com.coffeeorder.domain.outbox.service;

import java.time.Instant;

/**
 * DB lease를 획득한 뒤 트랜잭션 밖의 발행기로 전달하는 이벤트 snapshot.
 *
 * <p>{@code claimToken}은 외부 호출 후 결과를 반영할 때 동일 lease 소유자인지 확인하는 조건이다.
 */
public record ClaimedOrderEvent(
        String eventId, String payload, int attemptCount, String claimToken, Instant createdAt) {

    public ClaimedOrderEvent(String eventId, String payload, int attemptCount, String claimToken) {
        this(eventId, payload, attemptCount, claimToken, Instant.EPOCH);
    }
}
