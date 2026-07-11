package com.coffeeorder.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ResetFailedOutboxServiceIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-07-11T11:00:00.654321Z");

    @Autowired private OutboxDeliveryRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void resetsOnlyFailedAndStaleTokenCannotOverwriteReset() {
        String failed = insert("FAILED");
        String pending = insert("PENDING");
        ResetFailedOutboxService service = service();

        assertThat(service.reset(failed)).isTrue();
        assertThat(service.reset(pending)).isFalse();

        assertThat(row(failed))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0)
                .containsEntry("next_attempt_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .containsEntry("claim_token", null)
                .containsEntry("locked_by", null)
                .containsEntry("locked_until", null);
        ClaimedOrderEvent stale = new ClaimedOrderEvent(failed, "{}", 11, "old-token");
        assertThat(repository.markPublished(stale, NOW)).isZero();
        assertThat(repository.markFailed(stale, NOW, "late")).isZero();
        assertThat(row(failed)).containsEntry("status", "PENDING");
    }

    @Test
    void concurrentResetHasOneWinner() throws Exception {
        String failed = insert("FAILED");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = executor.submit(resetTask(start, failed));
            Future<Boolean> two = executor.submit(resetTask(start, failed));
            start.countDown();
            assertThat(List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private Callable<Boolean> resetTask(CountDownLatch start, String eventId) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            return service().reset(eventId);
        };
    }

    private ResetFailedOutboxService service() {
        return new ResetFailedOutboxService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String insert(String status) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    status, attempt_count, next_attempt_at, claim_token, locked_by, locked_until,
                    last_error, created_at, updated_at)
                VALUES (?, 'ORDER', ?, 'ORDER_PAID', 1, '{}', ?, 11, ?, 'old-token',
                        'old-worker', ?, 'old-error', ?, ?)
                """,
                eventId,
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1,
                status,
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(30)),
                Timestamp.from(NOW.minusSeconds(90)),
                Timestamp.from(NOW.minusSeconds(90)));
        return eventId;
    }

    private java.util.Map<String, Object> row(String eventId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, attempt_count, next_attempt_at, claim_token, locked_by, locked_until
                FROM outbox_events WHERE event_id = ?
                """,
                eventId);
    }
}
