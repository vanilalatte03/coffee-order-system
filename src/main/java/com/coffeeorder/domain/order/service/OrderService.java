package com.coffeeorder.domain.order.service;

import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 시점의 메뉴 정보를 주문 snapshot으로 영속화한다.
 *
 * <p>주문 ID는 포인트 결제 원장과 Outbox의 연결 대상이므로 flush로 확보한 뒤 상위 Facade에 반환한다.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /** 현재 시각을 한 번 캡처해 {@code paidAt}과 주문 생성 시각에 공통으로 사용한다. */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaidOrderResult create(CreatePaidOrderCommand command) {
        Instant paidAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Order order =
                orderRepository.saveAndFlush(
                        Order.paid(
                                command.userId(),
                                command.menuId(),
                                command.menuName(),
                                command.unitPrice(),
                                paidAt));
        return new PaidOrderResult(
                order.getId(),
                order.getUserId(),
                order.getMenuId(),
                order.getMenuNameSnapshot(),
                order.getPaidAmount(),
                order.getPaidAt());
    }
}
