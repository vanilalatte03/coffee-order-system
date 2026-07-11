package com.coffeeorder.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.global.observability.OperationalMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OutboxObservabilityIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-07-11T00:00:00Z");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxDeliveryRepository deliveryRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OperationalMetrics operationalMetrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void 성공_retry_격리_후_DB상태_gauge와_전달_counter가_일치한다() {
        double successBefore = deliveryResultCount("success");
        double retryBefore = deliveryResultCount("retryable_failure");
        double quarantineBefore = deliveryResultCount("quarantine");
        long firstLatencyBefore = timerCount("coffee.outbox.delivery.first.latency");
        AtomicInteger publishes = new AtomicInteger();

        insert("PENDING", 0, NOW.minusSeconds(5), NOW.minusSeconds(5));
        coordinator(
                        event -> {
                            publishes.incrementAndGet();
                            return OrderEventPublishResult.success();
                        })
                .dispatchNext("metrics-success");

        insert("PENDING", 1, NOW.minusSeconds(4), NOW.minusSeconds(4));
        coordinator(
                        event -> {
                            publishes.incrementAndGet();
                            return OrderEventPublishResult.retryableFailure("timeout");
                        })
                .dispatchNext("metrics-retry");

        insertProcessingAtAttemptLimit();
        coordinator(
                        event -> {
                            publishes.incrementAndGet();
                            return OrderEventPublishResult.success();
                        })
                .dispatchNext("metrics-quarantine");

        assertThat(deliveryResultCount("success") - successBefore).isEqualTo(1);
        assertThat(deliveryResultCount("retryable_failure") - retryBefore).isEqualTo(1);
        assertThat(deliveryResultCount("quarantine") - quarantineBefore).isEqualTo(1);
        assertThat(timerCount("coffee.outbox.delivery.first.latency") - firstLatencyBefore)
                .isEqualTo(1);
        assertThat(publishes).hasValue(2);
        assertThat(gauge("pending")).isEqualTo(1);
        assertThat(gauge("processing")).isZero();
        assertThat(gauge("failed")).isEqualTo(1);
        assertThat(meterRegistry.get("coffee.outbox.oldest.pending.seconds").gauge().value())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void 상태_metric_읽기는_Outbox_행_락을_기다리거나_선점하지_않는다() throws Exception {
        insert("PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(1));

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock =
                    connection.prepareStatement(
                            "SELECT event_id FROM outbox_events WHERE status = 'PENDING' FOR UPDATE")) {
                lock.executeQuery();
                try (var executor = Executors.newSingleThreadExecutor()) {
                    double pending =
                            executor.submit(() -> gauge("pending")).get(2, TimeUnit.SECONDS);
                    assertThat(pending).isEqualTo(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void lease를_잃은_stale_claim은_DB상태와_결과_metric을_확정하지_않는다(CapturedOutput output) {
        List<OrderEventPublishResult> results =
                List.of(
                        OrderEventPublishResult.success(),
                        OrderEventPublishResult.retryableFailure("timeout"),
                        OrderEventPublishResult.permanentFailure("bad request"));

        for (OrderEventPublishResult result : results) {
            jdbcTemplate.update("DELETE FROM outbox_events");
            String eventId = insert("PENDING", 0, NOW.minusSeconds(1), NOW.minusSeconds(1));
            String resultTag = result.type().name().toLowerCase();
            double resultBefore = deliveryResultCount(resultTag);
            double staleBefore = deliveryResultCount("stale_claim");

            coordinator(
                            event -> {
                                assertThat(event.eventId()).isEqualTo(eventId);
                                assertThat(
                                                jdbcTemplate.update(
                                                        """
                                                        UPDATE outbox_events
                                                        SET claim_token = ?, locked_by = 'new-owner', locked_until = ?
                                                        WHERE event_id = ? AND status = 'PROCESSING'
                                                        """,
                                                        UUID.randomUUID().toString(),
                                                        Timestamp.from(NOW.plusSeconds(60)),
                                                        eventId))
                                        .isEqualTo(1);
                                return result;
                            })
                    .dispatchNext("stale-worker");

            assertThat(status(eventId)).isEqualTo("PROCESSING");
            assertThat(deliveryResultCount(resultTag) - resultBefore).isZero();
            assertThat(deliveryResultCount("stale_claim") - staleBefore).isEqualTo(1);
        }

        assertThat(output)
                .contains("outbox_delivery_stale_claim")
                .doesNotContain("outbox_delivery_completed", "outbox_delivery_failed");
    }

    private OutboxDeliveryCoordinator coordinator(OrderEventPublisher publisher) {
        return new OutboxDeliveryCoordinator(
                deliveryRepository,
                publisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30),
                new OutboxBackoffPolicy(),
                () -> 1.0,
                transactionManager,
                operationalMetrics);
    }

    private String insert(String status, int attempts, Instant eligibleAt, Instant createdAt) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, 'ORDER', ?, 'ORDER_PAID', 1, '{}', ?, ?, ?, ?, ?)
                """,
                eventId,
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1,
                status,
                attempts,
                Timestamp.from(eligibleAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return eventId;
    }

    private void insertProcessingAtAttemptLimit() {
        String eventId = UUID.randomUUID().toString();
        insert("PENDING", 11, NOW.minusSeconds(10), NOW.minusSeconds(10));
        jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET event_id = ?, status = 'PROCESSING', claim_token = ?, locked_by = 'old-worker',
                    locked_until = ?
                WHERE status = 'PENDING' AND attempt_count = 11
                """,
                eventId,
                UUID.randomUUID().toString(),
                Timestamp.from(NOW.minusSeconds(1)));
    }

    private double deliveryResultCount(String result) {
        var counter =
                meterRegistry
                        .find("coffee.outbox.delivery.results")
                        .tag("result", result)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String status) {
        return meterRegistry.get("coffee.outbox.events").tag("status", status).gauge().value();
    }

    private String status(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
    }

    private long timerCount(String name) {
        var timer = meterRegistry.find(name).timer();
        return timer == null ? 0 : timer.count();
    }
}
