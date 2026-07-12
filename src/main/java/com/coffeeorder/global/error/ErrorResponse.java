package com.coffeeorder.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * 현재 HTTP 요청의 오류 메타데이터와 안정 비즈니스 오류 정보를 담는 공개 응답.
 *
 * <p>멱등성 재생은 {@code code}, {@code message}, {@code fieldErrors}만 저장하고, {@code timestamp}와 {@code
 * traceId}는 각 요청에서 새로 생성한다.
 */
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
