package com.coffeeorder.domain.outbox.service;

/**
 * Outbox 행이 주문 트랜잭션에 기록됐음을 알리는 로컬 wake-up 신호.
 *
 * <p>실제 전달 계약이나 영속 상태가 아니며, 커밋 뒤 listener가 빠른 첫 스캔을 예약하는 데만 사용한다.
 */
public record OrderEventRecorded(String eventId) {}
