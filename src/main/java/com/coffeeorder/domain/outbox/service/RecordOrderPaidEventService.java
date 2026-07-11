package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.entity.OutboxEvent;
import com.coffeeorder.domain.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecordOrderPaidEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public RecordOrderPaidEventService(
            OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

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
