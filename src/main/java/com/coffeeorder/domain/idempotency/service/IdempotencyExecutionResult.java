package com.coffeeorder.domain.idempotency.service;

/**
 * 멱등성 실행 후 반환할 안정 결과와 최초 실행 여부.
 *
 * <p>{@code replayed} 값은 Controller가 {@code Idempotency-Replayed} 응답 헤더를 정하는 데 사용한다.
 */
public record IdempotencyExecutionResult(IdempotencyResponseSnapshot snapshot, boolean replayed) {}
