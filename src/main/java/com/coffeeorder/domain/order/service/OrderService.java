package com.coffeeorder.domain.order.service;

import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

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
