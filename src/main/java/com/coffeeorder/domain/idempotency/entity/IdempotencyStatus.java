package com.coffeeorder.domain.idempotency.entity;

/** 멱등성 요청의 트랜잭션 내부 처리 상태와 재생 가능한 완료 상태. */
public enum IdempotencyStatus {
    PROCESSING,
    COMPLETED
}
