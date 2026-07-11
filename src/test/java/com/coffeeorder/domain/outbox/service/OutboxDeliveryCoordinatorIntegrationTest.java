package com.coffeeorder.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
class OutboxDeliveryCoordinatorIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-07-11T10:00:00.123456Z");

    @Autowired private OutboxDeliveryRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void pendingBoundaryIsInclusiveButFutureEventIsNotClaimed() {
        String ready = insertPending(NOW, 0);
        String future = insertPending(NOW.plusNanos(1_000), 0);
        AtomicInteger calls = new AtomicInteger();
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            calls.incrementAndGet();
                            return OrderEventPublishResult.success();
                        });

        assertThat(coordinator.dispatchNext("worker-a")).isTrue();
        assertThat(coordinator.dispatchNext("worker-a")).isFalse();

        assertThat(status(ready)).isEqualTo("PUBLISHED");
        assertThat(status(future)).isEqualTo("PENDING");
        assertThat(attemptCount(future)).isZero();
        assertThat(calls).hasValue(1);
    }

    @Test
    void leaseBoundaryIsExclusiveAndExpiredClaimGetsANewToken() {
        String atBoundary = insertProcessing(NOW, 3, "old-boundary");
        String expired = insertProcessing(NOW.minusNanos(1_000), 3, "old-expired");
        List<ClaimedOrderEvent> calls = new ArrayList<>();
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            calls.add(event);
                            return OrderEventPublishResult.success();
                        });

        assertThat(coordinator.dispatchNext("worker-b")).isTrue();
        assertThat(coordinator.dispatchNext("worker-b")).isFalse();

        assertThat(calls)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.eventId()).isEqualTo(expired);
                            assertThat(event.attemptCount()).isEqualTo(4);
                            assertThat(event.claimToken()).isNotEqualTo("old-expired");
                        });
        assertThat(status(atBoundary)).isEqualTo("PROCESSING");
        assertThat(attemptCount(atBoundary)).isEqualTo(3);
    }

    @Test
    void olderExpiredProcessingIsClaimedBeforeReadyPending() {
        String readyPending = insertPending(NOW, 0);
        String olderExpired = insertProcessing(NOW.minusSeconds(1), 3, "old-token");
        List<ClaimedOrderEvent> calls = new ArrayList<>();
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            calls.add(event);
                            return OrderEventPublishResult.success();
                        });

        assertThat(coordinator.dispatchNext("worker-fair")).isTrue();

        assertThat(calls)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.eventId()).isEqualTo(olderExpired);
                            assertThat(event.attemptCount()).isEqualTo(4);
                            assertThat(event.claimToken()).isNotEqualTo("old-token");
                        });
        assertThat(status(olderExpired)).isEqualTo("PUBLISHED");
        assertThat(status(readyPending)).isEqualTo("PENDING");
        assertThat(attemptCount(readyPending)).isZero();
    }

    @Test
    void expiredEleventhAttemptIsQuarantinedWithoutCallingPublisher() {
        String eventId = insertProcessing(NOW.minusSeconds(1), 11, "last-token");
        AtomicInteger calls = new AtomicInteger();
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            calls.incrementAndGet();
                            return OrderEventPublishResult.success();
                        });

        assertThat(coordinator.dispatchNext("worker-c")).isTrue();

        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(attemptCount(eventId)).isEqualTo(11);
        assertThat(nullableString(eventId, "claim_token")).isNull();
        assertThat(nullableString(eventId, "locked_by")).isNull();
        assertThat(nullableTimestamp(eventId, "locked_until")).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    void publisherRunsWithoutTransactionAndSuccessClearsClaim() {
        String eventId = insertPending(NOW, 0);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            transactionActive.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return OrderEventPublishResult.success();
                        });

        coordinator.dispatchNext("worker-d");

        assertThat(transactionActive).isFalse();
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
        assertThat(nullableTimestamp(eventId, "published_at")).isEqualTo(Timestamp.from(NOW));
        assertThat(nullableString(eventId, "claim_token")).isNull();
        assertThat(nullableTimestamp(eventId, "locked_until")).isNull();
    }

    @Test
    void retryableAndPermanentResultsFollowTheirStateTransitions() {
        String retryable = insertPending(NOW, 0);
        MutableClock retryClock = new MutableClock(NOW);
        OutboxDeliveryCoordinator retryCoordinator =
                coordinator(
                        retryClock, event -> OrderEventPublishResult.retryableFailure("timeout"));
        retryCoordinator.dispatchNext("worker-e");

        assertThat(status(retryable)).isEqualTo("PENDING");
        assertThat(timestamp(retryable, "next_attempt_at"))
                .isEqualTo(Timestamp.from(NOW.plusSeconds(1)));
        assertThat(nullableString(retryable, "last_error")).isEqualTo("timeout");

        retryClock.set(NOW.plusSeconds(1));
        OutboxDeliveryCoordinator permanentCoordinator =
                coordinator(
                        retryClock,
                        event -> OrderEventPublishResult.permanentFailure("bad request"));
        permanentCoordinator.dispatchNext("worker-e");
        assertThat(status(retryable)).isEqualTo("FAILED");
        assertThat(attemptCount(retryable)).isEqualTo(2);
        assertThat(nullableString(retryable, "last_error")).isEqualTo("bad request");
    }

    @Test
    void eleventhRetryableFailureFailsImmediatelyAndErrorIsBounded() {
        String eventId = insertPending(NOW, 10);
        AtomicInteger calls = new AtomicInteger();
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            calls.incrementAndGet();
                            return OrderEventPublishResult.retryableFailure("x".repeat(1200));
                        });

        assertThat(coordinator.dispatchNext("worker-f")).isTrue();
        assertThat(coordinator.dispatchNext("worker-f")).isFalse();

        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(attemptCount(eventId)).isEqualTo(11);
        assertThat(nullableString(eventId, "last_error")).hasSize(1000);
        assertThat(calls).hasValue(1);
    }

    @Test
    void staleTokenCannotOverwriteNewClaimResult() {
        String eventId = insertProcessing(NOW.minusSeconds(1), 1, "stale-token");
        OutboxDeliveryCoordinator coordinator =
                coordinator(NOW, event -> OrderEventPublishResult.success());
        coordinator.dispatchNext("worker-g");
        assertThat(repository.markPublished(eventId, "stale-token", NOW.plusSeconds(1))).isZero();
        assertThat(repository.markPending(eventId, "stale-token", NOW.plusSeconds(2), NOW, "late"))
                .isZero();
        assertThat(repository.markFailedByClaimToken(eventId, "stale-token", NOW, "late")).isZero();
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentWorkersClaimOneCandidateOnlyOnce() throws Exception {
        insertPending(NOW, 0);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch publisherEntered = new CountDownLatch(1);
        CountDownLatch releasePublisher = new CountDownLatch(1);
        OrderEventPublisher publisher =
                event -> {
                    calls.incrementAndGet();
                    publisherEntered.countDown();
                    await(releasePublisher);
                    return OrderEventPublishResult.success();
                };
        OutboxDeliveryCoordinator first = coordinator(NOW, publisher);
        OutboxDeliveryCoordinator second = coordinator(NOW, publisher);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> first.dispatchNext("worker-1"));
            assertThat(publisherEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> secondResult = executor.submit(() -> second.dispatchNext("worker-2"));
            assertThat(secondResult.get(5, TimeUnit.SECONDS)).isFalse();
            releasePublisher.countDown();
            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void concurrentWorkersDistributeDifferentCandidates() throws Exception {
        String firstId = insertPending(NOW, 0);
        String secondId = insertPending(NOW, 0);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<String> published = java.util.Collections.synchronizedList(new ArrayList<>());
        OrderEventPublisher publisher =
                event -> {
                    published.add(event.eventId());
                    entered.countDown();
                    await(release);
                    return OrderEventPublishResult.success();
                };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one =
                    executor.submit(() -> coordinator(NOW, publisher).dispatchNext("one"));
            Future<Boolean> two =
                    executor.submit(() -> coordinator(NOW, publisher).dispatchNext("two"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(published).containsExactlyInAnyOrder(firstId, secondId);
    }

    private OutboxDeliveryCoordinator coordinator(Instant instant, OrderEventPublisher publisher) {
        return coordinator(new MutableClock(instant), publisher);
    }

    private OutboxDeliveryCoordinator coordinator(
            MutableClock clock, OrderEventPublisher publisher) {
        return new OutboxDeliveryCoordinator(
                repository,
                publisher,
                clock,
                Duration.ofSeconds(30),
                new OutboxBackoffPolicy(),
                () -> 1.0,
                transactionManager);
    }

    private String insertPending(Instant nextAttemptAt, int attempts) {
        return insert("PENDING", attempts, nextAttemptAt, null, null);
    }

    private String insertProcessing(Instant lockedUntil, int attempts, String token) {
        return insert("PROCESSING", attempts, NOW.minusSeconds(30), token, lockedUntil);
    }

    private String insert(
            String status, int attempts, Instant nextAttemptAt, String token, Instant lockedUntil) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    status, attempt_count, next_attempt_at, claim_token, locked_by, locked_until,
                    created_at, updated_at)
                VALUES (?, 'ORDER', ?, 'ORDER_PAID', 1, '{}', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1,
                status,
                attempts,
                Timestamp.from(nextAttemptAt),
                token,
                token == null ? null : "old-worker",
                lockedUntil == null ? null : Timestamp.from(lockedUntil),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        return eventId;
    }

    private String status(String eventId) {
        return nullableString(eventId, "status");
    }

    private int attemptCount(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE event_id = ?",
                Integer.class,
                eventId);
    }

    private Timestamp timestamp(String eventId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM outbox_events WHERE event_id = ?",
                Timestamp.class,
                eventId);
    }

    private Timestamp nullableTimestamp(String eventId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM outbox_events WHERE event_id = ?",
                (resultSet, rowNum) -> resultSet.getTimestamp(1),
                eventId);
    }

    private String nullableString(String eventId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM outbox_events WHERE event_id = ?",
                (resultSet, rowNum) -> resultSet.getString(1),
                eventId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
