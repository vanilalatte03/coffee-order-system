package com.coffeeorder.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        Instant timestamp,
        String traceId,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorResponse> fieldErrors) {

    public static ErrorResponse of(
            Instant timestamp,
            String traceId,
            ErrorCode errorCode,
            List<FieldErrorResponse> fieldErrors) {
        return new ErrorResponse(
                timestamp, traceId, errorCode.code(), errorCode.message(), fieldErrors);
    }
}
