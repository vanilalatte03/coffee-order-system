package com.coffeeorder.domain.idempotency.service;

import com.coffeeorder.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

/**
 * 최초 실행 결과 중 재요청에서도 변하지 않아야 하는 HTTP 상태와 payload를 보관한다.
 *
 * <p>성공은 최초 응답 본문 전체를 저장한다. 결정적 오류는 요청별 값인 {@code timestamp}와 {@code traceId}를 제외한 안정 오류 payload만
 * 저장하고, 재생 시 현재 요청 메타데이터를 조립한다.
 */
public record IdempotencyResponseSnapshot(int responseStatus, String storedBody) {

    public IdempotencyResponseSnapshot {
        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("response status must be a valid HTTP status");
        }
        storedBody = CanonicalJson.normalize(storedBody);
        JsonNode body = CanonicalJson.read(storedBody);
        if (!isSuccess(responseStatus) && !body.isObject()) {
            throw new IllegalArgumentException(
                    "deterministic error snapshot must be a JSON object");
        }
        if (!isSuccess(responseStatus) && (body.has("timestamp") || body.has("traceId"))) {
            throw new IllegalArgumentException(
                    "deterministic error snapshot must not contain request metadata");
        }
    }

    public static IdempotencyResponseSnapshot success(int responseStatus, String responseBody) {
        if (!isSuccess(responseStatus)) {
            throw new IllegalArgumentException("success snapshot requires 2xx status");
        }
        return new IdempotencyResponseSnapshot(responseStatus, responseBody);
    }

    public static IdempotencyResponseSnapshot deterministicError(
            int responseStatus, String stableErrorPayload) {
        if (isSuccess(responseStatus)) {
            throw new IllegalArgumentException(
                    "deterministic error snapshot requires non-2xx status");
        }
        return new IdempotencyResponseSnapshot(responseStatus, stableErrorPayload);
    }

    public static IdempotencyResponseSnapshot deterministicError(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        ObjectNode stablePayload = JsonNodeFactory.instance.objectNode();
        stablePayload.put("code", errorCode.code());
        stablePayload.put("message", errorCode.message());
        return deterministicError(errorCode.status().value(), CanonicalJson.write(stablePayload));
    }

    public static IdempotencyResponseSnapshot restored(int responseStatus, String storedBody) {
        return new IdempotencyResponseSnapshot(responseStatus, storedBody);
    }

    public boolean deterministicError() {
        return !isSuccess(responseStatus);
    }

    /**
     * 현재 요청에 반환할 본문을 만든다.
     *
     * <p>성공은 저장한 본문을 그대로 반환하고, 결정적 오류만 현재 요청의 시각과 trace ID를 추가한다.
     */
    public String responseBody(Instant timestamp, String traceId) {
        if (!deterministicError()) {
            return storedBody;
        }
        if (timestamp == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("current error metadata is required");
        }
        JsonNode stablePayload = CanonicalJson.read(storedBody);
        if (!stablePayload.isObject()) {
            throw new IllegalStateException("deterministic error payload must be a JSON object");
        }
        ObjectNode responseBody = (ObjectNode) stablePayload.deepCopy();
        responseBody.put("timestamp", timestamp.toString());
        responseBody.put("traceId", traceId);
        return CanonicalJson.write(responseBody);
    }

    private static boolean isSuccess(int responseStatus) {
        return responseStatus >= 200 && responseStatus < 300;
    }
}
