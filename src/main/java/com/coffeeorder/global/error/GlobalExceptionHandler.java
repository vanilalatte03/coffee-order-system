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
                                                        ? ErrorCode.VALIDATION_ERROR.message()
                                                        : fieldError.getDefaultMessage()))
                        .toList();

        return errorResponse(ErrorCode.VALIDATION_ERROR, fieldErrors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return errorResponse(ErrorCode.VALIDATION_ERROR, List.of(), request);
    }

    @ExceptionHandler({
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleRequestValidation(
            Exception exception, HttpServletRequest request) {
        return errorResponse(ErrorCode.VALIDATION_ERROR, List.of(), request);
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<ErrorResponse> handleMissingIdempotencyKey(
            IdempotencyKeyRequiredException exception, HttpServletRequest request) {
        return errorResponse(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, List.of(), request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception, HttpServletRequest request) {
        return errorResponse(ErrorCode.INVALID_IDEMPOTENCY_KEY, List.of(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(
            UserNotFoundException exception, HttpServletRequest request) {
        return errorResponse(ErrorCode.USER_NOT_FOUND, List.of(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyReused(
            IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return errorResponse(ErrorCode.IDEMPOTENCY_KEY_REUSED, List.of(), request);
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
            return errorResponse(ErrorCode.DATABASE_UNAVAILABLE, List.of(), request, true);
        }
        log.error(
                "unclassified_database_failure errorType={} traceId={}",
                exception.getClass().getSimpleName(),
                TraceIdFilter.getTraceId(request));
        return errorResponse(ErrorCode.INTERNAL_SERVER_ERROR, List.of(), request);
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
        return errorResponse(ErrorCode.INTERNAL_SERVER_ERROR, List.of(), request);
    }

    private ResponseEntity<ErrorResponse> errorResponse(
            ErrorCode errorCode, List<FieldErrorResponse> fieldErrors, HttpServletRequest request) {
        return errorResponse(errorCode, fieldErrors, request, false);
    }

    private ResponseEntity<ErrorResponse> errorResponse(
            ErrorCode errorCode,
            List<FieldErrorResponse> fieldErrors,
            HttpServletRequest request,
            boolean retryable) {
        RequestObservability.result(request, errorCode.code());
        ErrorResponse response =
                ErrorResponse.of(
                        clock.instant(), TraceIdFilter.getTraceId(request), errorCode, fieldErrors);
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(errorCode.status()).contentType(JSON_UTF8);
        if (retryable) {
            builder.header("Retry-After", "1");
        }
        return builder.body(response);
    }

    private ResponseEntity<ErrorResponse> concurrencyTimeoutResponse(HttpServletRequest request) {
        return errorResponse(ErrorCode.CONCURRENCY_TIMEOUT, List.of(), request, true);
    }
}
