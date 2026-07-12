package com.coffeeorder.domain.order.controller;

import com.coffeeorder.domain.idempotency.service.IdempotencyKeyRequiredException;
import com.coffeeorder.domain.idempotency.service.InvalidIdempotencyKeyException;
import com.coffeeorder.domain.order.dto.CreateOrderRequest;
import com.coffeeorder.domain.order.service.CreateOrderCommand;
import com.coffeeorder.domain.order.service.CreateOrderResult;
import com.coffeeorder.domain.order.service.OrderFacade;
import com.coffeeorder.global.observability.RequestObservability;
import com.coffeeorder.global.observability.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final MediaType JSON_UTF8 =
            new MediaType("application", "json", StandardCharsets.UTF_8);

    private final OrderFacade orderFacade;
    private final Clock clock;

    public OrderController(OrderFacade orderFacade, Clock clock) {
        this.orderFacade = orderFacade;
        this.clock = clock;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest servletRequest) {
        RequestObservability.operation(servletRequest, "ORDER_CREATE");
        RequestObservability.user(servletRequest, request.userId());
        validateKey(idempotencyKey);
        CreateOrderResult result =
                orderFacade.create(
                        new CreateOrderCommand(request.userId(), request.menuId(), idempotencyKey),
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
