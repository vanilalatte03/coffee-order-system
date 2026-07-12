package com.coffeeorder.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.EnabledOutboxIntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
            "outbox.delivery.poll-interval=30s",
            "outbox.delivery.after-commit-wakeup-enabled=true"
        })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxAfterCommitWiringIntegrationTest extends EnabledOutboxIntegrationTestSupport {

    private static final OrderEventHttpStub HTTP_STUB = new OrderEventHttpStub();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureHttpStub(DynamicPropertyRegistry registry) {
        registry.add("outbox.delivery.base-url", () -> HTTP_STUB.baseUrl().toString());
        registry.add("outbox.delivery.connect-timeout", () -> "200ms");
        registry.add("outbox.delivery.read-timeout", () -> "500ms");
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

    @Test
    void orderCommitWakesProductionCycleWithoutCallingTheWorkerDirectly() throws Exception {
        mockMvc.perform(orderRequest("after-commit-success")).andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            assertThat(HTTP_STUB.requests()).hasSize(1);
                            assertThat(outboxStatus()).isEqualTo("PUBLISHED");
                        });

        String eventId = eventId();
        assertThat(HTTP_STUB.requests().getFirst().eventId()).isEqualTo(eventId);
        assertThat(
                        objectMapper
                                .readTree(HTTP_STUB.requests().getFirst().body())
                                .path("eventId")
                                .asText())
                .isEqualTo(eventId);
    }

    @Test
    void dataPlatformFailureDoesNotChangeTheCommittedOrderResponse() throws Exception {
        HTTP_STUB.respondWith(503);

        mockMvc.perform(orderRequest("platform-down")).andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> {
                            assertThat(HTTP_STUB.requests()).hasSize(1);
                            assertThat(outboxStatus()).isEqualTo("PENDING");
                        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT balance FROM point_wallets WHERE user_id = 10", Long.class))
                .isEqualTo(5000);
    }

    @Test
    void blockedDataPlatformDoesNotDelayTheCommittedCreatedResponse() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HTTP_STUB.blockResponses(requestEntered, releaseResponse);

        mockMvc.perform(orderRequest("platform-blocked")).andExpect(status().isCreated());
        assertThat(requestEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
                .isEqualTo(1);

        releaseResponse.countDown();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(outboxStatus()).isEqualTo("PUBLISHED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder orderRequest(
            String key) {
        return post("/api/v1/orders")
                .header("Idempotency-Key", key + "-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":10,\"menuId\":2}");
    }

    private String eventId() {
        return jdbcTemplate.queryForObject("SELECT event_id FROM outbox_events", String.class);
    }

    private String outboxStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM outbox_events", String.class);
    }
}
