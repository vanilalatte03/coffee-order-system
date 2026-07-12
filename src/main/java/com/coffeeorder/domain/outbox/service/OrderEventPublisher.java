package com.coffeeorder.domain.outbox.service;

/**
 * 선점된 주문 이벤트를 외부 전달 채널로 보내는 포트.
 *
 * <p>구현체는 DB 상태를 직접 바꾸지 않고, 재시도 가능 여부가 분류된 결과만 반환한다. 성공 응답 뒤의 프로세스 중단으로 중복 전달될 수 있으므로 수신자의 {@code
 * eventId} 멱등 처리가 필요하다.
 */
public interface OrderEventPublisher {

    OrderEventPublishResult publish(ClaimedOrderEvent event);
}
