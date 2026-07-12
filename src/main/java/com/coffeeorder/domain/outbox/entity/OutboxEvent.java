package com.coffeeorder.domain.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 주문 커밋과 원자적으로 저장되는 외부 전달용 내구성 이벤트.
 *
 * <p>payload는 발행 시 다시 계산하지 않는 불변 snapshot이며, 상태·시도 횟수·lease 정보는 작업자가 전달을 재개하거나 격리하는 데 사용한다.
 */
@Entity
@Table(
        name = "outbox_events",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_outbox_aggregate_event",
                        columnNames = {"aggregate_type", "aggregate_id", "event_type"}))
public class OutboxEvent {

    @Id
    @Column(name = "event_id", nullable = false, columnDefinition = "char(36)")
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private long aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private short schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claim_token", columnDefinition = "char(36)")
    private String claimToken;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxEvent() {}

    private OutboxEvent(String eventId, long aggregateId, String payload, Instant occurredAt) {
        if (aggregateId <= 0) {
            throw new IllegalArgumentException("aggregate id must be positive");
        }
        this.eventId = Objects.requireNonNull(eventId);
        this.aggregateType = "ORDER";
        this.aggregateId = aggregateId;
        this.eventType = "ORDER_PAID";
        this.schemaVersion = 1;
        this.payload = Objects.requireNonNull(payload);
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = Objects.requireNonNull(occurredAt);
        this.createdAt = occurredAt;
        this.updatedAt = occurredAt;
    }

    /**
     * V1의 유일한 Outbox 계약인 {@code ORDER_PAID} 이벤트를 대기 상태로 만든다.
     *
     * <p>{@code occurredAt}은 주문의 {@code paidAt}과 같은 시각이며 첫 발행 예약 시각으로도 사용한다.
     */
    public static OutboxEvent orderPaid(
            String eventId, long orderId, String payload, Instant occurredAt) {
        return new OutboxEvent(eventId, orderId, payload, occurredAt);
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public short getSchemaVersion() {
        return schemaVersion;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
