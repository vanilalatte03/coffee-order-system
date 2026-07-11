package com.coffeeorder.domain.ranking.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.MySqlIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(PopularMenuApiIntegrationTest.ClockTestConfiguration.class)
class PopularMenuApiIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant FIXED_TO = Instant.parse("2026-07-10T04:40:00.123456Z");
    private static final Instant FIXED_FROM = Instant.parse("2026-07-03T04:40:00.123456Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestClock testClock;

    @BeforeEach
    void setUp() {
        testClock.setInstant(FIXED_TO);
    }

    @Test
    void 정확히_from인_주문은_포함하고_to인_주문과_기간_밖_주문은_제외한다() throws Exception {
        insertPaidOrder(1, FIXED_FROM, "하한 스냅샷", 4100);
        insertPaidOrder(1, FIXED_TO, "상한 스냅샷", 4200);
        insertPaidOrder(1, FIXED_FROM.minusNanos(1000), "기간 밖 스냅샷", 4300);

        mockMvc.perform(get("/api/v1/menus/popular"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.from").value("2026-07-03T04:40:00.123456Z"))
                .andExpect(jsonPath("$.to").value("2026-07-10T04:40:00.123456Z"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].menuId").value(1))
                .andExpect(jsonPath("$.items[0].orderCount").value(1));
    }

    @Test
    void PAID가_아닌_주문과_INACTIVE_메뉴를_제외한다() throws Exception {
        jdbcTemplate.execute("ALTER TABLE orders DROP CHECK chk_orders_status");
        try {
            insertPaidOrder(1, FIXED_TO.minusSeconds(1), "집계 대상", 4500);
            insertOrder(2, "PENDING", FIXED_TO.minusSeconds(2), "비결제 주문", 5000);
            insertPaidOrder(4, FIXED_TO.minusSeconds(3), "비활성 메뉴", 6000);

            mockMvc.perform(get("/api/v1/menus/popular"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].menuId").value(1))
                    .andExpect(jsonPath("$.items[0].orderCount").value(1));
        } finally {
            jdbcTemplate.update("DELETE FROM orders");
            jdbcTemplate.execute(
                    "ALTER TABLE orders ADD CONSTRAINT chk_orders_status CHECK (status = 'PAID')");
        }
    }

    @Test
    void 주문수_내림차순과_메뉴_ID_오름차순으로_정렬하고_세_개만_반환한다() throws Exception {
        jdbcTemplate.update("UPDATE menus SET status = 'ACTIVE' WHERE id = 4");
        insertOrders(1, 2);
        insertOrders(2, 2);
        insertOrders(3, 3);
        insertOrders(4, 1);

        mockMvc.perform(get("/api/v1/menus/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].menuId").value(3))
                .andExpect(jsonPath("$.items[0].orderCount").value(3))
                .andExpect(jsonPath("$.items[1].rank").value(2))
                .andExpect(jsonPath("$.items[1].menuId").value(1))
                .andExpect(jsonPath("$.items[2].rank").value(3))
                .andExpect(jsonPath("$.items[2].menuId").value(2));
    }

    @Test
    void 대상이_없으면_같은_경계와_빈_items를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/menus/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-07-03T04:40:00.123456Z"))
                .andExpect(jsonPath("$.to").value("2026-07-10T04:40:00.123456Z"))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 현재_메뉴_이름과_가격을_반환하고_주문_스냅샷_행_수를_집계한다() throws Exception {
        jdbcTemplate.update("UPDATE menus SET name = '리뉴얼 아메리카노', price = 4900 WHERE id = 1");
        insertPaidOrder(1, FIXED_TO.minusSeconds(1), "예전 이름 A", 4000);
        insertPaidOrder(1, FIXED_TO.minusSeconds(2), "예전 이름 B", 4100);

        mockMvc.perform(get("/api/v1/menus/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("리뉴얼 아메리카노"))
                .andExpect(jsonPath("$.items[0].price").value(4900))
                .andExpect(jsonPath("$.items[0].orderCount").value(2));
    }

    @Test
    void 나노초_Clock을_microsecond로_절삭한_경계를_API와_query가_공유한다() throws Exception {
        testClock.setInstant(Instant.parse("2026-07-10T04:40:00.123456789Z"));
        insertPaidOrder(1, FIXED_FROM, "절삭 하한", 4500);
        insertPaidOrder(2, FIXED_TO, "절삭 상한", 5000);

        mockMvc.perform(get("/api/v1/menus/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-07-03T04:40:00.123456Z"))
                .andExpect(jsonPath("$.to").value("2026-07-10T04:40:00.123456Z"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].menuId").value(1));
    }

    private void insertOrders(long menuId, int count) {
        for (int index = 0; index < count; index++) {
            insertPaidOrder(
                    menuId, FIXED_TO.minusSeconds(index + 1L), "주문 스냅샷 " + menuId, 4000 + menuId);
        }
    }

    private void insertPaidOrder(
            long menuId, Instant paidAt, String menuNameSnapshot, long unitPrice) {
        insertOrder(menuId, "PAID", paidAt, menuNameSnapshot, unitPrice);
    }

    private void insertOrder(
            long menuId, String status, Instant paidAt, String menuNameSnapshot, long unitPrice) {
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    user_id, menu_id, menu_name_snapshot, unit_price, quantity,
                    paid_amount, status, paid_at, created_at
                ) VALUES (10, ?, ?, ?, 1, ?, ?, ?, ?)
                """,
                menuId,
                menuNameSnapshot,
                unitPrice,
                unitPrice,
                status,
                Timestamp.from(paidAt),
                Timestamp.from(paidAt));
    }

    @TestConfiguration
    static class ClockTestConfiguration {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(FIXED_TO);
        }
    }

    static final class TestClock extends Clock {

        private Instant instant;

        private TestClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
