package com.coffeeorder.global.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.MySqlIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("1단계 관측성 통합 테스트")
class Phase11ObservabilityIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 0 WHERE user_id = 10");
    }

    @DisplayName("API, 멱등성, 인기 조회를 저카디널리티 태그로 계측한다")
    @Test
    void API와_멱등성과_인기_query를_저카디널리티_태그로_계측한다() throws Exception {
        double firstBefore = idempotencyCount("first");
        double replayBefore = idempotencyCount("replay");
        double conflictBefore = idempotencyCount("conflict");
        double apiBefore = apiCount("SUCCESS");
        long rankingBefore = timerCount("coffee.ranking.query.duration");

        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "phase11-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isCreated());
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "phase11-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isCreated());
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "phase11-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":200}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/menus/popular")).andExpect(status().isOk());

        assertThat(idempotencyCount("first") - firstBefore).isEqualTo(1);
        assertThat(idempotencyCount("replay") - replayBefore).isEqualTo(1);
        assertThat(idempotencyCount("conflict") - conflictBefore).isEqualTo(1);
        assertThat(apiCount("SUCCESS") - apiBefore).isGreaterThanOrEqualTo(2);
        assertThat(timerCount("coffee.ranking.query.duration") - rankingBefore).isEqualTo(1);
        assertThat(meterRegistry.find("coffee.api.requests").tag("userId", "10").meter()).isNull();
        assertThat(meterRegistry.find("coffee.api.requests").tag("orderId", "1").meter()).isNull();
        assertThat(meterRegistry.find("coffee.api.requests").tag("eventId", "event").meter())
                .isNull();
    }

    @DisplayName("Actuator는 사용자 지정 메트릭만 조회하고 민감한 엔드포인트는 노출하지 않는다")
    @Test
    void Actuator는_custom_metric만_조회하고_민감_endpoint는_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/menus")).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics/coffee.api.requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("coffee.api.requests"));
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
    }

    private double idempotencyCount(String outcome) {
        var counter =
                meterRegistry
                        .find("coffee.idempotency.requests")
                        .tags("operation", "point_charge", "outcome", outcome)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    private double apiCount(String resultCode) {
        var counter =
                meterRegistry
                        .find("coffee.api.requests")
                        .tags(
                                "method",
                                "POST",
                                "endpoint",
                                "/api/v1/users/{userId}/points/charges",
                                "result_code",
                                resultCode)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String name) {
        var timer = meterRegistry.find(name).timer();
        return timer == null ? 0 : timer.count();
    }
}
