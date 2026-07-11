package com.coffeeorder.global.error;

import com.coffeeorder.domain.idempotency.service.IdempotencyInProgressException;
import com.coffeeorder.domain.idempotency.service.IdempotencyKeyRequiredException;
import com.coffeeorder.domain.idempotency.service.IdempotencyKeyReusedException;
import com.coffeeorder.domain.idempotency.service.InvalidIdempotencyKeyException;
import com.coffeeorder.domain.user.service.UserNotFoundException;
import com.coffeeorder.global.observability.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String VALIDATION_MESSAGE = "요청 값이 올바르지 않습니다.";
    private static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";
    private static final String INTERNAL_SERVER_ERROR_MESSAGE = "서버 오류가 발생했습니다.";
    private static final MediaType JSON_UTF8 =
            new MediaType("application", "json", StandardCharsets.UTF_8);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldErrorResponse> fieldErrors =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                fieldError ->
                                        new FieldErrorResponse(
                                                fieldError.getField(),
                                                fieldError.getDefaultMessage() == null
                                                        ? VALIDATION_MESSAGE
                                                        : fieldError.getDefaultMessage()))
                        .toList();

        return errorResponse(
                HttpStatus.BAD_REQUEST, VALIDATION_ERROR, VALIDATION_MESSAGE, fieldErrors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return errorResponse(
                HttpStatus.BAD_REQUEST, VALIDATION_ERROR, VALIDATION_MESSAGE, List.of(), request);
    }

    @ExceptionHandler({
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleRequestValidation(
            Exception exception, HttpServletRequest request) {
        return errorResponse(
                HttpStatus.BAD_REQUEST, VALIDATION_ERROR, VALIDATION_MESSAGE, List.of(), request);
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<ErrorResponse> handleMissingIdempotencyKey(
            IdempotencyKeyRequiredException exception, HttpServletRequest request) {
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key 헤더가 필요합니다.",
                List.of(),
                request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception, HttpServletRequest request) {
        return errorResponse(
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key 형식이 올바르지 않습니다.",
                List.of(),
                request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(
            UserNotFoundException exception, HttpServletRequest request) {
        return errorResponse(
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.", List.of(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return errorResponse(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "동일한 멱등 키가 다른 요청에 사용되었습니다.",
                List.of(),
                request);
    }

    @ExceptionHandler({
        PessimisticLockingFailureException.class,
        IdempotencyInProgressException.class
    })
    public ResponseEntity<ErrorResponse> handleConcurrencyTimeout(
            Exception exception, HttpServletRequest request) {
        ErrorResponse response =
                new ErrorResponse(
                        clock.instant(),
                        TraceIdFilter.getTraceId(request),
                        "CONCURRENCY_TIMEOUT",
                        "동시 요청 처리 대기 시간이 초과되었습니다.",
                        List.of());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .contentType(JSON_UTF8)
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("unhandled request failure", exception);
        return errorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_SERVER_ERROR,
                INTERNAL_SERVER_ERROR_MESSAGE,
                List.of(),
                request);
    }

    private ResponseEntity<ErrorResponse> errorResponse(
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorResponse> fieldErrors,
            HttpServletRequest request) {
        ErrorResponse response =
                new ErrorResponse(
                        clock.instant(),
                        TraceIdFilter.getTraceId(request),
                        code,
                        message,
                        fieldErrors);
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(response);
    }
}
