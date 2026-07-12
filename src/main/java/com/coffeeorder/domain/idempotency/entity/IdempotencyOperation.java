package com.coffeeorder.domain.idempotency.entity;

/** 같은 Idempotency-Key라도 서로 독립된 결과를 보관할 쓰기 유스케이스 구분자. */
public enum IdempotencyOperation {
    POINT_CHARGE,
    ORDER_CREATE
}
