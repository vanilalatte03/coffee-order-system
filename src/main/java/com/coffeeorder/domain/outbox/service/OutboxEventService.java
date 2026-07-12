package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.entity.OutboxEvent;
import com.coffeeorder.domain.outbox.repository.OutboxEventRepository;
import com.coffeeorder.global.observability.TraceIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 트랜잭션에 {@code ORDER_PAID} Outbox 이벤트를 기록하고 커밋 후 전달 신호를 발행한다.
 *
 * <p>Spring application event는 실제 전달이 아니라 로컬 wake-up 힌트다. listener가 {@code AFTER_COMMIT} 단계에서만
 * 실행되므로 롤백된 주문을 발행하지 않으며, 신호가 유실돼도 주기 스캔이 DB의 {@code PENDING} 행을 복구한다.
 */
@Service
public class OutboxEventService {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public OutboxEventService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 주문 결제 시각을 그대로 가진 불변 이벤트 snapshot을 저장한다.
     *
     * <p>이 메서드는 상위 주문 트랜잭션에 참여한다. Outbox 저장에 실패하면 주문과 포인트 차감도 함께 롤백된다.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RecordedOutboxEventResult record(RecordOrderPaidEventCommand command) {
        Instant occurredAt =
                Objects.requireNonNull(command.occurredAt()).truncatedTo(ChronoUnit.MICROS);
        String eventId = UUID.randomUUID().toString();
        OrderPaidEventPayload payload =
                new OrderPaidEventPayload(
                        1,
                        eventId,
                        "ORDER_PAID",
                        occurredAt,
                        command.orderId(),
                        command.userId(),
                        command.menuId(),
                        command.paymentAmount());
        OutboxEvent event =
                outboxEventRepository.save(
                        OutboxEvent.orderPaid(
                                eventId, command.orderId(), serialize(payload), occurredAt));
        applicationEventPublisher.publishEvent(new OrderEventRecorded(eventId));
        log.info(
                "outbox_event_record_attempted traceId={} userId={} orderId={} eventId={} operation=ORDER_PAID resultCode=PENDING_COMMIT",
                TraceIdFilter.currentTraceId(),
                command.userId(),
                command.orderId(),
                eventId);
        return new RecordedOutboxEventResult(
                event.getEventId(),
                event.getPayload(),
                event.getStatus(),
                event.getAttemptCount(),
                event.getNextAttemptAt());
    }

    private String serialize(OrderPaidEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("ORDER_PAID payload cannot be serialized", exception);
        }
    }
}
