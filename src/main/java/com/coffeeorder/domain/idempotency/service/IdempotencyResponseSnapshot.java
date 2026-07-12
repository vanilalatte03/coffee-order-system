package com.coffeeorder.domain.idempotency.service;

import com.coffeeorder.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

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
