package com.coffeeorder.domain.point.controller;

import com.coffeeorder.domain.idempotency.service.IdempotencyKeyRequiredException;
import com.coffeeorder.domain.idempotency.service.InvalidIdempotencyKeyException;
import com.coffeeorder.domain.point.dto.ChargePointsRequest;
import com.coffeeorder.domain.point.service.ChargePointsCommand;
import com.coffeeorder.domain.point.service.ChargePointsResult;
import com.coffeeorder.domain.point.service.PointFacade;
import com.coffeeorder.global.observability.RequestObservability;
import com.coffeeorder.global.observability.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/users/{userId}/points/charges")
public class PointChargeController {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final MediaType JSON_UTF8 =
            new MediaType("application", "json", StandardCharsets.UTF_8);

    private final PointFacade pointFacade;
    private final Clock clock;

    public PointChargeController(PointFacade pointFacade, Clock clock) {
        this.pointFacade = pointFacade;
        this.clock = clock;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> charge(
            @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") long userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChargePointsRequest request,
            HttpServletRequest servletRequest) {
        RequestObservability.operation(servletRequest, "POINT_CHARGE");
        RequestObservability.user(servletRequest, userId);
        validateKey(idempotencyKey);
        ChargePointsResult result =
                pointFacade.charge(
                        new ChargePointsCommand(userId, request.amount(), idempotencyKey),
                        clock.instant(),
                        TraceIdFilter.getTraceId(servletRequest));
        RequestObservability.resultFromResponse(
                servletRequest, result.status(), result.responseBody());
        return ResponseEntity.status(result.status())
                .contentType(JSON_UTF8)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.responseBody());
    }

    private static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IdempotencyKeyRequiredException();
        }
        if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
