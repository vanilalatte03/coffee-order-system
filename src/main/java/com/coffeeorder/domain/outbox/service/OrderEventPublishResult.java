package com.coffeeorder.domain.outbox.service;

import java.util.Objects;

public record OrderEventPublishResult(Type type, String error) {

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
