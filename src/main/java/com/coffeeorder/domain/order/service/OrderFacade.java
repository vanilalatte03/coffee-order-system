package com.coffeeorder.domain.order.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.service.CanonicalPayload;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutionResult;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutor;
import com.coffeeorder.domain.idempotency.service.IdempotencyResponseSnapshot;
import com.coffeeorder.domain.menu.service.MenuNotFoundException;
import com.coffeeorder.domain.menu.service.MenuNotOrderableException;
import com.coffeeorder.domain.menu.service.MenuService;
import com.coffeeorder.domain.menu.service.OrderableMenuResult;
import com.coffeeorder.domain.order.dto.CreateOrderResponse;
import com.coffeeorder.domain.order.dto.OrderMenuResponse;
import com.coffeeorder.domain.outbox.service.OutboxEventService;
import com.coffeeorder.domain.outbox.service.RecordOrderPaidEventCommand;
import com.coffeeorder.domain.point.service.PointPaymentPreparation;
import com.coffeeorder.domain.point.service.PointWriteService;
import com.coffeeorder.domain.user.service.UserService;
import com.coffeeorder.global.error.ErrorCode;
import com.coffeeorder.global.observability.RequestObservability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 주문·결제의 멱등성 경계와 도메인 쓰기 순서를 조정한다.
 *
 * <p>메뉴 검증, 지갑 잠금, 주문·원장·Outbox 기록과 완료 응답 저장은 {@link IdempotencyExecutor}가 하나의 쓰기 트랜잭션으로 묶는다. 외부
 * 데이터 플랫폼 호출은 이 흐름에 포함하지 않고 Outbox에만 기록한다.
 */
@Service
public class OrderFacade {

    private static final Logger log = LoggerFactory.getLogger(OrderFacade.class);

    private final IdempotencyExecutor idempotencyExecutor;
    private final UserService userService;
    private final MenuService menuService;
    private final PointWriteService pointWriteService;
    private final OrderService orderService;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;

    public OrderFacade(
            IdempotencyExecutor idempotencyExecutor,
            UserService userService,
            MenuService menuService,
            PointWriteService pointWriteService,
            OrderService orderService,
            OutboxEventService outboxEventService,
            ObjectMapper objectMapper) {
        this.idempotencyExecutor = idempotencyExecutor;
        this.userService = userService;
        this.menuService = menuService;
        this.pointWriteService = pointWriteService;
        this.orderService = orderService;
        this.outboxEventService = outboxEventService;
        this.objectMapper = objectMapper;
    }

    /**
     * 메뉴 한 개를 수량 1개로 결제하고 최초 결과 또는 재생 결과를 반환한다.
     *
     * <p>최초 실행의 순서는 메뉴 검증, 지갑 잠금·잔액 확인, {@code PAID} 주문 저장, 포인트 차감 원장, {@code ORDER_PAID} Outbox
     * 기록이다. 메뉴 오류와 잔액 부족은 도메인 쓰기 없이 결정적 오류 snapshot으로 완료되며, 저장 실패는 전체 트랜잭션을 롤백한다.
     */
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
                        () -> userService.validateExists(command.userId()),
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
            menu = menuService.validateOrderable(command.menuId());
        } catch (MenuNotFoundException exception) {
            return IdempotencyResponseSnapshot.deterministicError(ErrorCode.MENU_NOT_FOUND);
        } catch (MenuNotOrderableException exception) {
            return IdempotencyResponseSnapshot.deterministicError(ErrorCode.MENU_NOT_ORDERABLE);
        }

        PointPaymentPreparation payment =
                pointWriteService.preparePayment(command.userId(), menu.price());
        if (!payment.sufficient()) {
            return IdempotencyResponseSnapshot.deterministicError(ErrorCode.INSUFFICIENT_POINTS);
        }

        PaidOrderResult order =
                orderService.create(
                        new CreatePaidOrderCommand(
                                command.userId(), menu.menuId(), menu.name(), menu.price()));
        long remainingBalance =
                pointWriteService.completePayment(
                        payment.paymentLock(), order.userId(), order.orderId());
        outboxEventService.record(
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
