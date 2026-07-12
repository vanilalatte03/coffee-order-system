package com.coffeeorder.global.error;

import com.coffeeorder.domain.idempotency.service.IdempotencyInProgressException;
import com.coffeeorder.domain.idempotency.service.IdempotencyKeyRequiredException;
import com.coffeeorder.domain.idempotency.service.IdempotencyKeyReusedException;
import com.coffeeorder.domain.idempotency.service.InvalidIdempotencyKeyException;
import com.coffeeorder.domain.user.service.UserNotFoundException;
import com.coffeeorder.global.observability.OperationalMetrics;
import com.coffeeorder.global.observability.RequestObservability;
import com.coffeeorder.global.observability.TraceIdFilter;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
    private final OperationalMetrics metrics;

    public GlobalExceptionHandler(Clock clock, ObjectProvider<OperationalMetrics> metrics) {
        this.clock = clock;
        this.metrics = metrics.getIfAvailable(OperationalMetrics::fallback);
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
        LockTimeoutException.class,
        PessimisticLockException.class
    })
    public ResponseEntity<ErrorResponse> handleConcurrencyTimeout(
            Exception exception, HttpServletRequest request) {
        DatabaseFailureClassifier.Failure failure = DatabaseFailureClassifier.classify(exception);
        metrics.increment("coffee.database.failures", "type", failure.name().toLowerCase());
        log.warn(
                "database_concurrency_failure errorType={} classification={} traceId={}",
                exception.getClass().getSimpleName(),
                failure.name(),
                TraceIdFilter.getTraceId(request));
        return concurrencyTimeoutResponse(request);
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyInProgress(
            IdempotencyInProgressException exception, HttpServletRequest request) {
        log.warn(
                "idempotency_concurrency_timeout errorType={} traceId={}",
                exception.getClass().getSimpleName(),
                TraceIdFilter.getTraceId(request));
        return concurrencyTimeoutResponse(request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDatabaseFailure(
            DataAccessException exception, HttpServletRequest request) {
        DatabaseFailureClassifier.Failure failure = DatabaseFailureClassifier.classify(exception);
        if (failure == DatabaseFailureClassifier.Failure.LOCK_TIMEOUT
                || failure == DatabaseFailureClassifier.Failure.DEADLOCK) {
            return handleConcurrencyTimeout(exception, request);
        }
        metrics.increment("coffee.database.failures", "type", failure.name().toLowerCase());
        if (failure == DatabaseFailureClassifier.Failure.UNAVAILABLE) {
            log.warn(
                    "database_unavailable errorType={} traceId={}",
                    exception.getClass().getSimpleName(),
                    TraceIdFilter.getTraceId(request));
            return errorResponse(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATABASE_UNAVAILABLE",
                    "데이터베이스를 일시적으로 사용할 수 없습니다.",
                    List.of(),
                    request,
                    true);
        }
        log.error(
                "unclassified_database_failure errorType={} traceId={}",
                exception.getClass().getSimpleName(),
                TraceIdFilter.getTraceId(request));
        return errorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_SERVER_ERROR,
                INTERNAL_SERVER_ERROR_MESSAGE,
                List.of(),
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error(
                "unhandled_request_failure errorType={} traceId={}",
                exception.getClass().getSimpleName(),
                TraceIdFilter.getTraceId(request));
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
        return errorResponse(status, code, message, fieldErrors, request, false);
    }

    private ResponseEntity<ErrorResponse> errorResponse(
            HttpStatus status,
            String code,
            String message,
            List<FieldErrorResponse> fieldErrors,
            HttpServletRequest request,
            boolean retryable) {
        RequestObservability.result(request, code);
        ErrorResponse response =
                new ErrorResponse(
                        clock.instant(),
                        TraceIdFilter.getTraceId(request),
                        code,
                        message,
                        fieldErrors);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(JSON_UTF8);
        if (retryable) {
            builder.header("Retry-After", "1");
        }
        return builder.body(response);
    }

    private ResponseEntity<ErrorResponse> concurrencyTimeoutResponse(HttpServletRequest request) {
        RequestObservability.result(request, "CONCURRENCY_TIMEOUT");
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
}
