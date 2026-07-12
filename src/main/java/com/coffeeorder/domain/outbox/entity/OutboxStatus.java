package com.coffeeorder.domain.outbox.entity;

/** 내구성 Outbox 이벤트의 전달 생명주기 상태. */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
