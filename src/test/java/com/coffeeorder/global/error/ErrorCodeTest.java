package com.coffeeorder.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("오류 코드")
class ErrorCodeTest {

    @DisplayName("1단계 공개 오류의 HTTP 상태 코드와 기본 메시지를 정의한다")
    @Test
    void Phase_1_공개_오류의_HTTP_상태_코드_기본_메시지를_정의한다() {
        assertThat(ErrorCode.values())
                .extracting(ErrorCode::status, ErrorCode::code, ErrorCode::message)
                .containsExactly(
                        tuple(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값이 올바르지 않습니다."),
                        tuple(
                                HttpStatus.BAD_REQUEST,
                                "IDEMPOTENCY_KEY_REQUIRED",
                                "Idempotency-Key 헤더가 필요합니다."),
                        tuple(
                                HttpStatus.BAD_REQUEST,
                                "INVALID_IDEMPOTENCY_KEY",
                                "Idempotency-Key 형식이 올바르지 않습니다."),
                        tuple(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."),
                        tuple(HttpStatus.NOT_FOUND, "MENU_NOT_FOUND", "메뉴를 찾을 수 없습니다."),
                        tuple(HttpStatus.CONFLICT, "MENU_NOT_ORDERABLE", "주문할 수 없는 메뉴입니다."),
                        tuple(HttpStatus.CONFLICT, "INSUFFICIENT_POINTS", "포인트 잔액이 부족합니다."),
                        tuple(
                                HttpStatus.CONFLICT,
                                "IDEMPOTENCY_KEY_REUSED",
                                "동일한 멱등 키가 다른 요청에 사용되었습니다."),
                        tuple(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "POINT_BALANCE_OVERFLOW",
                                "포인트 잔액이 범위를 초과합니다."),
                        tuple(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "CONCURRENCY_TIMEOUT",
                                "동시 요청 처리 대기 시간이 초과되었습니다."),
                        tuple(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "DATABASE_UNAVAILABLE",
                                "데이터베이스를 일시적으로 사용할 수 없습니다."),
                        tuple(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "INTERNAL_SERVER_ERROR",
                                "서버 오류가 발생했습니다."));
    }
}
