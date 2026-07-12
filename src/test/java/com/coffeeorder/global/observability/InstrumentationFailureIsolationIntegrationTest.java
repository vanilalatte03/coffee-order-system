package com.coffeeorder.global.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.domain.outbox.repository.OutboxObservabilityRepository;
import com.coffeeorder.domain.outbox.service.OrderEventPublishResult;
import com.coffeeorder.domain.outbox.service.OutboxBackoffPolicy;
import com.coffeeorder.domain.outbox.service.OutboxDeliveryCoordinator;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InstrumentationFailureIsolationIntegrationTest.FailingMetricsConfiguration.class)
@DisplayName("계측 실패 격리 통합 테스트")
class InstrumentationFailureIsolationIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-07-11T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxDeliveryRepository outboxDeliveryRepository;
    @Autowired private OutboxObservabilityRepository outboxObservabilityRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OperationalMetrics operationalMetrics;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 10000 WHERE user_id = 10");
        jdbcTemplate.update("UPDATE menus SET status = 'ACTIVE' WHERE id = 2");
    }

    @DisplayName("레지스트리 예외에도 충전과 주문 커밋 및 원래 오류 응답을 유지한다")
    @Test
    void registry_예외에도_충전과_주문_commit과_원래_오류_응답을_유지한다() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "metrics-fail-charge")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isCreated());
        mockMvc.perform(
                        post("/api/v1/orders")
                                .header("Idempotency-Key", "metrics-fail-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userId\":10,\"menuId\":2}"))
                .andExpect(status().isCreated());
        mockMvc.perform(
                        post("/api/v1/users/9999/points/charges")
                                .header("Idempotency-Key", "metrics-fail-error")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(count("point_transactions")).isEqualTo(2);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_requests")).isEqualTo(2);
        assertThat(balance()).isEqualTo(5100);
    }

    @DisplayName("레지스트리 예외에도 아웃박스 성공 및 재시도 전이를 유지한다")
    @Test
    void registry_예외에도_Outbox_success와_retry_전이를_유지한다() {
        String successEvent = insertPending(0);
        coordinator(event -> OrderEventPublishResult.success())
                .dispatchNext("metrics-fail-success");

        String retryEvent = insertPending(0);
        coordinator(event -> OrderEventPublishResult.retryableFailure("timeout"))
                .dispatchNext("metrics-fail-retry");

        assertThat(outboxStatus(successEvent)).isEqualTo("PUBLISHED");
        assertThat(outboxStatus(retryEvent)).isEqualTo("PENDING");
        assertThat(attemptCount(successEvent)).isEqualTo(1);
        assertThat(attemptCount(retryEvent)).isEqualTo(1);
    }

    @DisplayName("레지스트리와 게이지 조회 예외를 업무 밖으로 격리한다")
    @Test
    void registry와_gauge_query_예외를_업무_밖으로_격리한다() {
        assertThatCode(
                        () ->
                                new OutboxStateMetrics(
                                        outboxObservabilityRepository, operationalMetrics))
                .doesNotThrowAnyException();

        OutboxObservabilityRepository failingRepository = mock(OutboxObservabilityRepository.class);
        when(failingRepository.count(any(OutboxStatus.class)))
                .thenThrow(new IllegalStateException("forced gauge query failure"));
        when(failingRepository.oldestPendingWaitSeconds())
                .thenThrow(new IllegalStateException("forced gauge query failure"));
        SimpleMeterRegistry readableRegistry = new SimpleMeterRegistry();
        new OutboxStateMetrics(failingRepository, new OperationalMetrics(readableRegistry));

        assertThat(
                        readableRegistry
                                .get("coffee.outbox.events")
                                .tag("status", "pending")
                                .gauge()
                                .value())
                .isNaN();
        assertThat(readableRegistry.get("coffee.outbox.oldest.pending.seconds").gauge().value())
                .isNaN();
    }

    private OutboxDeliveryCoordinator coordinator(
            com.coffeeorder.domain.outbox.service.OrderEventPublisher publisher) {
        return new OutboxDeliveryCoordinator(
                outboxDeliveryRepository,
                publisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30),
                new OutboxBackoffPolicy(),
                () -> 1.0,
                transactionManager,
                operationalMetrics);
    }

    private String insertPending(int attempts) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, 'ORDER', ?, 'ORDER_PAID', 1, '{}', 'PENDING', ?, ?, ?, ?)
                """,
                eventId,
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1,
                attempts,
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(1)));
        return eventId;
    }

    private String outboxStatus(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
    }

    private int attemptCount(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE event_id = ?",
                Integer.class,
                eventId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long balance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM point_wallets WHERE user_id = 10", Long.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingMetricsConfiguration {

        @Bean
        @Primary
        OperationalMetrics failingOperationalMetrics() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.config()
                    .meterFilter(
                            new MeterFilter() {
                                @Override
                                public Meter.Id map(Meter.Id id) {
                                    throw new IllegalStateException("forced registry failure");
                                }
                            });
            return new OperationalMetrics(registry);
        }
    }
}
