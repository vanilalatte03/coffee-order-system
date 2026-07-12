package com.coffeeorder.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key 형식이 올바르지 않습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다."),
    MENU_NOT_ORDERABLE(HttpStatus.CONFLICT, "주문할 수 없는 메뉴입니다."),
    INSUFFICIENT_POINTS(HttpStatus.CONFLICT, "포인트 잔액이 부족합니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "동일한 멱등 키가 다른 요청에 사용되었습니다."),
    POINT_BALANCE_OVERFLOW(HttpStatus.UNPROCESSABLE_ENTITY, "포인트 잔액이 범위를 초과합니다."),
    CONCURRENCY_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "동시 요청 처리 대기 시간이 초과되었습니다."),
    DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "데이터베이스를 일시적으로 사용할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return name();
    }

    public String message() {
        return message;
    }
}
