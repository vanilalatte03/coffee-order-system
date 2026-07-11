package com.coffeeorder.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.menu.service.MenuNotOrderableException;
import com.coffeeorder.domain.menu.service.OrderableMenuResult;
import com.coffeeorder.domain.menu.service.ValidateOrderableMenuService;
import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import com.coffeeorder.domain.outbox.service.RecordOrderPaidEventCommand;
import com.coffeeorder.domain.outbox.service.RecordOrderPaidEventService;
import com.coffeeorder.domain.outbox.service.RecordedOutboxEventResult;
import com.coffeeorder.domain.point.service.PointWriteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(OrderWriteModelIntegrationTest.FixedClockConfiguration.class)
class OrderWriteModelIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant CLOCK_INSTANT = Instant.parse("2026-07-11T04:35:00.456789123Z");
    private static final Instant NORMALIZED_INSTANT = Instant.parse("2026-07-11T04:35:00.456789Z");

    @Autowired private ValidateOrderableMenuService validateOrderableMenuService;
    @Autowired private CreatePaidOrderService createPaidOrderService;
    @Autowired private PointWriteService pointWriteService;
    @Autowired private RecordOrderPaidEventService recordOrderPaidEventService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update(
                "UPDATE point_wallets SET balance = 10000, updated_at = UTC_TIMESTAMP(6)");
        jdbcTemplate.update(
                "UPDATE menus SET name = '카페라떼', price = 5000, status = 'ACTIVE' WHERE id = 2");
        jdbcTemplate.update("UPDATE menus SET status = 'INACTIVE' WHERE id = 4");
    }

    @Test
    void 저장한_주문_ID로_같은_transaction에서_PAYMENT와_Outbox를_연결한다() throws Exception {
        OrderableMenuResult menu = validateOrderableMenuService.validate(2);

        WorkflowResult result = transactionTemplate.execute(status -> writeOrder(menu));

        assertThat(result).isNotNull();
        Map<String, Object> order =
                jdbcTemplate.queryForMap(
                        """
                        SELECT user_id, menu_id, menu_name_snapshot, unit_price, quantity,
                               paid_amount, status, paid_at
                        FROM orders WHERE id = ?
                        """,
                        result.order().orderId());
        assertThat(order)
                .containsEntry("user_id", 10L)
                .containsEntry("menu_id", 2L)
                .containsEntry("menu_name_snapshot", "카페라떼")
                .containsEntry("unit_price", 5000L)
                .containsEntry("quantity", 1)
                .containsEntry("paid_amount", 5000L)
                .containsEntry("status", "PAID");
        assertThat((LocalDateTime) order.get("paid_at"))
                .isEqualTo(LocalDateTime.ofInstant(NORMALIZED_INSTANT, ZoneOffset.UTC));

        assertThat(jdbcTemplate.queryForMap("SELECT * FROM point_transactions"))
                .containsEntry("user_id", 10L)
                .containsEntry("order_id", result.order().orderId())
                .containsEntry("type", "PAYMENT")
                .containsEntry("amount", 5000L)
                .containsEntry("balance_after", 5000L);
        assertThat(result.remainingBalance()).isEqualTo(5000);

        JsonNode payload = objectMapper.readTree(result.outbox().payload());
        assertThat(payload)
                .isEqualTo(
                        objectMapper.readTree(
                                """
                                {
                                  "schemaVersion": 1,
                                  "eventId": "%s",
                                  "eventType": "ORDER_PAID",
                                  "occurredAt": "2026-07-11T04:35:00.456789Z",
                                  "orderId": %d,
                                  "userId": 10,
                                  "menuId": 2,
                                  "paymentAmount": 5000
                                }
                                """
                                        .formatted(
                                                result.outbox().eventId(),
                                                result.order().orderId())));
        assertThat(result.order().paidAt()).isEqualTo(NORMALIZED_INSTANT);
        assertThat(payload.get("occurredAt").asText())
                .isEqualTo(result.order().paidAt().toString());
        assertThat(result.outbox().status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(result.outbox().attemptCount()).isZero();
        assertThat(result.outbox().nextAttemptAt()).isEqualTo(NORMALIZED_INSTANT);
    }

    @Test
    void 주문_snapshot은_저장_뒤_현재_메뉴가_바뀌어도_변하지_않는다() {
        OrderableMenuResult menu = validateOrderableMenuService.validate(2);
        WorkflowResult result = transactionTemplate.execute(status -> writeOrder(menu));

        jdbcTemplate.update("UPDATE menus SET name = '새 이름', price = 9000 WHERE id = 2");

        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT menu_name_snapshot, unit_price, paid_amount FROM orders WHERE id = ?",
                                result.order().orderId()))
                .containsEntry("menu_name_snapshot", "카페라떼")
                .containsEntry("unit_price", 5000L)
                .containsEntry("paid_amount", 5000L);
    }

    @Test
    void 비활성_메뉴는_주문_모델_생성_전에_거절한다() {
        assertThatThrownBy(() -> validateOrderableMenuService.validate(4))
                .isInstanceOf(MenuNotOrderableException.class);

        assertThat(count("orders")).isZero();
        assertThat(count("point_transactions")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void DB는_주문별_PAYMENT와_ORDER_PAID_중복을_각각_거절한다() {
        WorkflowResult result =
                transactionTemplate.execute(
                        status -> writeOrder(validateOrderableMenuService.validate(2)));

        assertThatThrownBy(
                        () ->
                                transactionTemplate.execute(
                                        status ->
                                                pointWriteService.pay(
                                                        10, result.order().orderId(), 5000)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(balanceOf(10)).isEqualTo(5000);
        assertThat(count("point_transactions")).isOne();

        assertThatThrownBy(
                        () ->
                                transactionTemplate.execute(
                                        status ->
                                                recordOrderPaidEventService.record(
                                                        eventCommand(result.order()))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("outbox_events")).isOne();
    }

    private WorkflowResult writeOrder(OrderableMenuResult menu) {
        PaidOrderResult order =
                createPaidOrderService.create(
                        new CreatePaidOrderCommand(10, menu.menuId(), menu.name(), menu.price()));
        long remainingBalance = pointWriteService.pay(10, order.orderId(), order.paymentAmount());
        RecordedOutboxEventResult outbox = recordOrderPaidEventService.record(eventCommand(order));
        return new WorkflowResult(order, remainingBalance, outbox);
    }

    private RecordOrderPaidEventCommand eventCommand(PaidOrderResult order) {
        return new RecordOrderPaidEventCommand(
                order.orderId(),
                order.userId(),
                order.menuId(),
                order.paymentAmount(),
                order.paidAt());
    }

    private long balanceOf(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM point_wallets WHERE user_id = ?", Long.class, userId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record WorkflowResult(
            PaidOrderResult order, long remainingBalance, RecordedOutboxEventResult outbox) {}

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
        }
    }
}
