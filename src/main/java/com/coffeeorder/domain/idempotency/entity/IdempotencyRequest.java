package com.coffeeorder.domain.idempotency.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 최초 완료 응답을 보존하는 멱등성 요청 상태.
 *
 * <p>{@code PROCESSING}은 도메인 쓰기와 같은 트랜잭션에서만 존재하며, 성공 또는 결정적 비즈니스 오류는 {@code COMPLETED}로 전이해 재생 가능한
 * snapshot을 갖는다. 일시적 실패는 이 엔티티까지 롤백한다.
 */
@Entity
@Table(
        name = "idempotency_requests",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_idempotency_scope",
                        columnNames = {"user_id", "operation", "idempotency_key"}))
public class IdempotencyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IdempotencyOperation operation;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, columnDefinition = "char(64)")
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Short responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "json")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRequest() {}

    private IdempotencyRequest(
            long userId,
            IdempotencyOperation operation,
            String idempotencyKey,
            String requestHash,
            Instant createdAt) {
        this.userId = userId;
        this.operation = Objects.requireNonNull(operation);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.status = IdempotencyStatus.PROCESSING;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static IdempotencyRequest processing(
            long userId,
            IdempotencyOperation operation,
            String idempotencyKey,
            String requestHash,
            Instant createdAt) {
        return new IdempotencyRequest(userId, operation, idempotencyKey, requestHash, createdAt);
    }

    /**
     * 현재 실행의 재생 가능한 HTTP 결과를 기록하고 완료 상태로 전이한다.
     *
     * <p>한 번 완료된 결과는 이후 요청의 현재 시각이나 trace ID와 분리된 안정 payload로 유지된다.
     */
    public void complete(int responseStatus, String responseBody, Instant completedAt) {
        if (status != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("only processing idempotency request can complete");
        }
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = (short) responseStatus;
        this.responseBody = Objects.requireNonNull(responseBody);
        this.completedAt = Objects.requireNonNull(completedAt);
    }

    public boolean hasRequestHash(String candidateHash) {
        return requestHash.equals(candidateHash);
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean isProcessing() {
        return status == IdempotencyStatus.PROCESSING;
    }

    public int getResponseStatus() {
        if (!isCompleted() || responseStatus == null || responseBody == null) {
            throw new IllegalStateException("idempotency request is not completed");
        }
        return responseStatus;
    }

    public String getResponseBody() {
        if (!isCompleted() || responseStatus == null || responseBody == null) {
            throw new IllegalStateException("idempotency request is not completed");
        }
        return responseBody;
    }
}
