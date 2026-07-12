package com.coffeeorder.domain.order.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.service.CanonicalPayload;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutionResult;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutor;
import com.coffeeorder.domain.idempotency.service.IdempotencyResponseSnapshot;
import com.coffeeorder.domain.menu.service.MenuNotFoundException;
import com.coffeeorder.domain.menu.service.MenuNotOrderableException;
import com.coffeeorder.domain.menu.service.OrderableMenuResult;
import com.coffeeorder.domain.menu.service.ValidateOrderableMenuService;
import com.coffeeorder.domain.order.dto.CreateOrderResponse;
import com.coffeeorder.domain.order.dto.OrderMenuResponse;
import com.coffeeorder.domain.outbox.service.RecordOrderPaidEventCommand;
import com.coffeeorder.domain.outbox.service.RecordOrderPaidEventService;
import com.coffeeorder.domain.point.service.PointPaymentPreparation;
import com.coffeeorder.domain.point.service.PointWriteService;
import com.coffeeorder.domain.user.service.ValidateUserService;
import com.coffeeorder.global.observability.RequestObservability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderFacade {

    private static final Logger log = LoggerFactory.getLogger(OrderFacade.class);

    private static final String MENU_NOT_FOUND_BODY =
            "{\"code\":\"MENU_NOT_FOUND\",\"message\":\"메뉴를 찾을 수 없습니다.\"}";
    private static final String MENU_NOT_ORDERABLE_BODY =
            "{\"code\":\"MENU_NOT_ORDERABLE\",\"message\":\"주문할 수 없는 메뉴입니다.\"}";
    private static final String INSUFFICIENT_POINTS_BODY =
            "{\"code\":\"INSUFFICIENT_POINTS\",\"message\":\"포인트 잔액이 부족합니다.\"}";

    private final IdempotencyExecutor idempotencyExecutor;
    private final ValidateUserService validateUserService;
    private final ValidateOrderableMenuService validateOrderableMenuService;
    private final PointWriteService pointWriteService;
    private final CreatePaidOrderService createPaidOrderService;
    private final RecordOrderPaidEventService recordOrderPaidEventService;
    private final ObjectMapper objectMapper;

    public OrderFacade(
            IdempotencyExecutor idempotencyExecutor,
            ValidateUserService validateUserService,
            ValidateOrderableMenuService validateOrderableMenuService,
            PointWriteService pointWriteService,
            CreatePaidOrderService createPaidOrderService,
            RecordOrderPaidEventService recordOrderPaidEventService,
            ObjectMapper objectMapper) {
        this.idempotencyExecutor = idempotencyExecutor;
        this.validateUserService = validateUserService;
        this.validateOrderableMenuService = validateOrderableMenuService;
        this.pointWriteService = pointWriteService;
        this.createPaidOrderService = createPaidOrderService;
        this.recordOrderPaidEventService = recordOrderPaidEventService;
        this.objectMapper = objectMapper;
    }

    public CreateOrderResult create(
            CreateOrderCommand command, Instant responseTimestamp, String traceId) {
        CanonicalPayload payload =
                CanonicalPayload.fromJson(
                        "{\"menuId\":"
                                + command.menuId()
                                + ",\"userId\":"
                                + command.userId()
                                + "}");
        IdempotencyExecutionResult execution =
                idempotencyExecutor.execute(
                        command.userId(),
                        IdempotencyOperation.ORDER_CREATE,
                        command.idempotencyKey(),
                        payload,
                        () -> validateUserService.validateExists(command.userId()),
                        () -> executeOrder(command, traceId));
        IdempotencyResponseSnapshot snapshot = execution.snapshot();
        String responseBody = snapshot.responseBody(responseTimestamp, traceId);
        log.info(
                "order_request_completed traceId={} userId={} operation=ORDER_CREATE resultCode={} replayed={}",
                traceId,
                command.userId(),
                RequestObservability.resultCode(snapshot.responseStatus(), responseBody),
                execution.replayed());
        return new CreateOrderResult(snapshot.responseStatus(), responseBody, execution.replayed());
    }

    private IdempotencyResponseSnapshot executeOrder(CreateOrderCommand command, String traceId) {
        OrderableMenuResult menu;
        try {
            menu = validateOrderableMenuService.validate(command.menuId());
        } catch (MenuNotFoundException exception) {
            return IdempotencyResponseSnapshot.deterministicError(404, MENU_NOT_FOUND_BODY);
        } catch (MenuNotOrderableException exception) {
            return IdempotencyResponseSnapshot.deterministicError(409, MENU_NOT_ORDERABLE_BODY);
        }

        PointPaymentPreparation payment =
                pointWriteService.preparePayment(command.userId(), menu.price());
        if (!payment.sufficient()) {
            return IdempotencyResponseSnapshot.deterministicError(409, INSUFFICIENT_POINTS_BODY);
        }

        PaidOrderResult order =
                createPaidOrderService.create(
                        new CreatePaidOrderCommand(
                                command.userId(), menu.menuId(), menu.name(), menu.price()));
        long remainingBalance =
                pointWriteService.completePayment(
                        payment.paymentLock(), order.userId(), order.orderId());
        recordOrderPaidEventService.record(
                new RecordOrderPaidEventCommand(
                        order.orderId(),
                        order.userId(),
                        order.menuId(),
                        order.paymentAmount(),
                        order.paidAt()));
        log.info(
                "order_payment_attempted traceId={} userId={} orderId={} operation=ORDER_CREATE resultCode=PENDING_COMMIT",
                traceId,
                order.userId(),
                order.orderId());

        CreateOrderResponse response =
                new CreateOrderResponse(
                        order.orderId(),
                        order.userId(),
                        new OrderMenuResponse(order.menuId(), order.menuName()),
                        order.paymentAmount(),
                        1,
                        order.paymentAmount(),
                        remainingBalance,
                        "PAID",
                        order.paidAt());
        return IdempotencyResponseSnapshot.success(201, writeJson(response));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("order response cannot be serialized", exception);
        }
    }
}
