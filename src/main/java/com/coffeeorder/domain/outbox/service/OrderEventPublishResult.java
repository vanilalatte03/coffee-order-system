package com.coffeeorder.domain.outbox.service;

import java.util.Objects;

/**
 * 외부 발행 결과와 Outbox 상태 전이에 필요한 실패 분류.
 *
 * <p>재시도 가능 실패는 예약 대기열로 되돌리고, 영구 실패는 즉시 격리한다.
 */
public record OrderEventPublishResult(Type type, String error) {

    /** Outbox coordinator가 해석하는 외부 발행 결과 종류. */
    public enum Type {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    public OrderEventPublishResult {
        Objects.requireNonNull(type);
        if (type != Type.SUCCESS && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("failure result must include an error");
        }
    }

    public static OrderEventPublishResult success() {
        return new OrderEventPublishResult(Type.SUCCESS, null);
    }

    public static OrderEventPublishResult retryableFailure(String error) {
        return new OrderEventPublishResult(Type.RETRYABLE_FAILURE, error);
    }

    public static OrderEventPublishResult permanentFailure(String error) {
        return new OrderEventPublishResult(Type.PERMANENT_FAILURE, error);
    }
}
