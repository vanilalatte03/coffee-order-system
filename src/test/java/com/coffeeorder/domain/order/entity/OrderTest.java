package com.coffeeorder.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void 결제_주문은_메뉴와_가격_수량_상태를_snapshot으로_보존한다() {
        Instant paidAt = Instant.parse("2026-07-11T04:35:00.456789Z");

        Order order = Order.paid(10, 2, "카페라떼", 5000, paidAt);

        assertThat(order.getUserId()).isEqualTo(10);
        assertThat(order.getMenuId()).isEqualTo(2);
        assertThat(order.getMenuNameSnapshot()).isEqualTo("카페라떼");
        assertThat(order.getUnitPrice()).isEqualTo(5000);
        assertThat(order.getQuantity()).isOne();
        assertThat(order.getPaidAmount()).isEqualTo(5000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
    }
}
