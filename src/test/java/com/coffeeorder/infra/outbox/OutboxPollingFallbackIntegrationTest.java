package com.coffeeorder.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.EnabledOutboxIntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "outbox.delivery.enabled=true",
            "outbox.delivery.poll-interval=1s",
            "outbox.delivery.after-commit-wakeup-enabled=false"
        })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("아웃박스 폴링 대체 경로 통합 테스트")
class OutboxPollingFallbackIntegrationTest extends EnabledOutboxIntegrationTestSupport {

    private static final OrderEventHttpStub HTTP_STUB = new OrderEventHttpStub();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureHttpStub(DynamicPropertyRegistry registry) {
        registry.add("outbox.delivery.base-url", () -> HTTP_STUB.baseUrl().toString());
    }

    @BeforeEach
    void resetData() {
        HTTP_STUB.reset();
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update(
                "UPDATE point_wallets SET balance = 10000, updated_at = UTC_TIMESTAMP(6)");
    }

    @AfterAll
    static void stopStub() {
        HTTP_STUB.close();
    }

    @DisplayName("커밋 후 깨우기가 억제되어도 1초 폴링이 이벤트를 찾는다")
    @Test
    void oneSecondPollingFindsTheEventWhenAfterCommitWakeupIsSuppressed() throws Exception {
        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Idempotency-Key", "polling-fallback")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userId\":10,\"menuId\":2}"))
                .andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            assertThat(HTTP_STUB.requests()).hasSize(1);
                            assertThat(
                                            jdbcTemplate.queryForObject(
                                                    "SELECT status FROM outbox_events",
                                                    String.class))
                                    .isEqualTo("PUBLISHED");
                        });
    }
}
