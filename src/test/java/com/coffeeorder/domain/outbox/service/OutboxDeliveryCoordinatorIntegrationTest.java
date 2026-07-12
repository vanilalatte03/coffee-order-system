package com.coffeeorder.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository.LockedCandidate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@DisplayName("아웃박스 전달 조정기 통합 테스트")
class OutboxDeliveryCoordinatorIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-07-11T10:00:00.123456Z");

    @Autowired private OutboxDeliveryRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @DisplayName("대기 경계는 포함하고 미래 이벤트는 선점하지 않는다")
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

    @DisplayName("리스 경계는 제외하고 만료된 선점에는 새 토큰을 부여한다")
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

    @DisplayName("오래된 만료 처리 이벤트를 준비된 대기 이벤트보다 먼저 선점한다")
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

    @DisplayName("만료된 11번째 시도는 발행자 호출 없이 격리한다")
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

    @DisplayName("발행자는 트랜잭션 밖에서 실행되고 성공하면 선점을 해제한다")
    @Test
    void publisherRunsWithoutTransactionAndSuccessClearsClaim() {
        String eventId = insertPending(NOW, 0);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicBoolean claimVisible = new AtomicBoolean(false);
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            transactionActive.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            Map<String, Object> claimed = claimRow(eventId);
                            claimVisible.set(
                                    claimed.get("status").equals("PROCESSING")
                                            && claimed.get("attempt_count").equals(1)
                                            && claimed.get("claim_token").equals(event.claimToken())
                                            && claimed.get("locked_by").equals("worker-d")
                                            && nullableTimestamp(eventId, "locked_until")
                                                    .equals(Timestamp.from(NOW.plusSeconds(30))));
                            return OrderEventPublishResult.success();
                        });

        coordinator.dispatchNext("worker-d");

        assertThat(transactionActive).isFalse();
        assertThat(claimVisible).isTrue();
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
        assertThat(nullableTimestamp(eventId, "published_at")).isEqualTo(Timestamp.from(NOW));
        assertThat(nullableString(eventId, "claim_token")).isNull();
        assertThat(nullableString(eventId, "locked_by")).isNull();
        assertThat(nullableTimestamp(eventId, "locked_until")).isNull();
    }

    @DisplayName("재시도 가능 및 영구 결과는 상태 전이를 따른다")
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
        assertClaimFieldsCleared(retryable);

        retryClock.set(NOW.plusSeconds(1));
        OutboxDeliveryCoordinator permanentCoordinator =
                coordinator(
                        retryClock,
                        event -> OrderEventPublishResult.permanentFailure("bad request"));
        permanentCoordinator.dispatchNext("worker-e");
        assertThat(status(retryable)).isEqualTo("FAILED");
        assertThat(attemptCount(retryable)).isEqualTo(2);
        assertThat(nullableString(retryable, "last_error")).isEqualTo("bad request");
        assertClaimFieldsCleared(retryable);
    }

    @DisplayName("발행자 예외는 재시도 가능 실패가 되고 선점을 해제한다")
    @Test
    void publisherExceptionBecomesRetryableFailureAndClearsClaim() {
        String eventId = insertPending(NOW, 0);
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            throw new IllegalStateException("boom");
                        });

        assertThat(coordinator.dispatchNext("worker-exception")).isTrue();

        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(attemptCount(eventId)).isEqualTo(1);
        assertThat(timestamp(eventId, "next_attempt_at"))
                .isEqualTo(Timestamp.from(NOW.plusSeconds(1)));
        assertThat(nullableString(eventId, "last_error")).isEqualTo("IllegalStateException: boom");
        assertClaimFieldsCleared(eventId);
    }

    @DisplayName("11번째 재시도 가능 실패는 즉시 실패 처리하고 오류를 제한한다")
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

    @DisplayName("오래된 토큰은 처리 중인 새 선점을 덮어쓸 수 없다")
    @Test
    void staleTokenCannotOverwriteNewClaimWhileItIsProcessing() {
        String eventId = insertProcessing(NOW.minusSeconds(1), 1, "stale-token");
        AtomicBoolean staleResultsRejected = new AtomicBoolean(false);
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        NOW,
                        event -> {
                            assertThat(status(eventId)).isEqualTo("PROCESSING");
                            assertThat(nullableString(eventId, "claim_token"))
                                    .isEqualTo(event.claimToken())
                                    .isNotEqualTo("stale-token");
                            assertThat(
                                            repository.markPublished(
                                                    eventId, "stale-token", NOW.plusSeconds(1)))
                                    .isZero();
                            assertThat(
                                            repository.markPending(
                                                    eventId,
                                                    "stale-token",
                                                    NOW.plusSeconds(2),
                                                    NOW,
                                                    "late retry"))
                                    .isZero();
                            assertThat(
                                            repository.markFailedByClaimToken(
                                                    eventId, "stale-token", NOW, "late failure"))
                                    .isZero();
                            assertThat(status(eventId)).isEqualTo("PROCESSING");
                            assertThat(nullableString(eventId, "claim_token"))
                                    .isEqualTo(event.claimToken());
                            staleResultsRejected.set(true);
                            return OrderEventPublishResult.success();
                        });
        coordinator.dispatchNext("worker-g");
        assertThat(staleResultsRejected).isTrue();
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @DisplayName("리스는 후보 잠금을 획득한 뒤 시작한다")
    @Test
    void leaseStartsAfterCandidateLockIsAcquired() {
        String eventId = insertPending(NOW, 0);
        MutableClock clock = new MutableClock(NOW);
        AtomicBoolean leaseUsesPostLockTime = new AtomicBoolean(false);
        OutboxDeliveryRepository advancingRepository =
                new OutboxDeliveryRepository(jdbcTemplate) {
                    @Override
                    public Optional<LockedCandidate> lockNextCandidate(
                            Instant eligibilityAt, int maxAttempts) {
                        Optional<LockedCandidate> candidate =
                                super.lockNextCandidate(eligibilityAt, maxAttempts);
                        clock.set(NOW.plusSeconds(20));
                        return candidate;
                    }
                };
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        advancingRepository,
                        clock,
                        event -> {
                            leaseUsesPostLockTime.set(
                                    nullableTimestamp(eventId, "locked_until")
                                            .equals(Timestamp.from(NOW.plusSeconds(50))));
                            return OrderEventPublishResult.success();
                        });

        coordinator.dispatchNext("worker-after-lock");

        assertThat(leaseUsesPostLockTime).isTrue();
        assertThat(nullableTimestamp(eventId, "published_at"))
                .isEqualTo(Timestamp.from(NOW.plusSeconds(20)));
        assertThat(timestamp(eventId, "updated_at")).isEqualTo(Timestamp.from(NOW.plusSeconds(20)));
    }

    @DisplayName("나노초 시계 값은 데이터베이스 마이크로초로 정규화한다")
    @Test
    void nanosecondClockValuesAreNormalizedToDatabaseMicroseconds() {
        Instant nanosecondNow = Instant.parse("2026-07-11T10:00:00.123456789Z");
        Instant normalizedNow = Instant.parse("2026-07-11T10:00:00.123456Z");
        String eventId = insertPending(normalizedNow, 0);
        AtomicBoolean normalizedClaimVisible = new AtomicBoolean(false);
        OutboxDeliveryCoordinator coordinator =
                coordinator(
                        nanosecondNow,
                        event -> {
                            normalizedClaimVisible.set(
                                    nullableTimestamp(eventId, "locked_until")
                                            .equals(Timestamp.from(normalizedNow.plusSeconds(30))));
                            return OrderEventPublishResult.success();
                        });

        coordinator.dispatchNext("worker-nanos");

        assertThat(normalizedClaimVisible).isTrue();
        assertThat(nullableTimestamp(eventId, "published_at"))
                .isEqualTo(Timestamp.from(normalizedNow));

        String retryableEventId = insertPending(normalizedNow, 0);
        OutboxDeliveryCoordinator retryableCoordinator =
                coordinator(
                        nanosecondNow,
                        event -> OrderEventPublishResult.retryableFailure("temporary"));

        retryableCoordinator.dispatchNext("worker-nanos-retry");

        assertThat(timestamp(retryableEventId, "next_attempt_at"))
                .isEqualTo(Timestamp.from(normalizedNow.plusSeconds(1)));
    }

    @DisplayName("첫 번째 행 잠금 중에도 SKIP LOCKED는 두 번째 후보를 선택한다")
    @Test
    void skipLockedSelectsSecondCandidateWhileFirstRowLockIsHeld() throws Exception {
        String firstId = insertPending(NOW.minusSeconds(1), 0);
        String secondId = insertPending(NOW, 0);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> lockHolder =
                    executor.submit(
                            () ->
                                    transaction.executeWithoutResult(
                                            ignored -> {
                                                jdbcTemplate.queryForObject(
                                                        "SELECT event_id FROM outbox_events WHERE event_id = ? FOR UPDATE",
                                                        String.class,
                                                        firstId);
                                                rowLocked.countDown();
                                                await(releaseLock);
                                            }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            List<String> published = new ArrayList<>();
            Future<Boolean> dispatcher =
                    executor.submit(
                            () ->
                                    coordinator(
                                                    NOW,
                                                    event -> {
                                                        published.add(event.eventId());
                                                        return OrderEventPublishResult.success();
                                                    })
                                            .dispatchNext("skip-locked-worker"));

            assertThat(dispatcher.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(published).containsExactly(secondId);
            assertThat(status(firstId)).isEqualTo("PENDING");
            releaseLock.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
        }
    }

    @DisplayName("동시 작업자는 후보 하나를 한 번만 선점한다")
    @Test
    void concurrentWorkersClaimOneCandidateOnlyOnce() throws Exception {
        String eventId = insertPending(NOW, 0);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        OrderEventPublisher publisher =
                event -> {
                    calls.incrementAndGet();
                    return OrderEventPublishResult.success();
                };
        OutboxDeliveryCoordinator first = coordinator(NOW, publisher);
        OutboxDeliveryCoordinator second = coordinator(NOW, publisher);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult =
                    executor.submit(() -> dispatchWhenStarted(first, "worker-1", ready, start));
            Future<Boolean> secondResult =
                    executor.submit(() -> dispatchWhenStarted(second, "worker-2", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(
                            List.of(
                                    firstResult.get(5, TimeUnit.SECONDS),
                                    secondResult.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(calls).hasValue(1);
        assertThat(attemptCount(eventId)).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @DisplayName("동시 작업자는 서로 다른 후보를 분배한다")
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
        return coordinator(repository, clock, publisher);
    }

    private OutboxDeliveryCoordinator coordinator(
            OutboxDeliveryRepository deliveryRepository,
            MutableClock clock,
            OrderEventPublisher publisher) {
        return new OutboxDeliveryCoordinator(
                deliveryRepository,
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

    private Map<String, Object> claimRow(String eventId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, attempt_count, claim_token, locked_by, locked_until
                FROM outbox_events WHERE event_id = ?
                """,
                eventId);
    }

    private void assertClaimFieldsCleared(String eventId) {
        assertThat(nullableString(eventId, "claim_token")).isNull();
        assertThat(nullableString(eventId, "locked_by")).isNull();
        assertThat(nullableTimestamp(eventId, "locked_until")).isNull();
    }

    private static boolean dispatchWhenStarted(
            OutboxDeliveryCoordinator coordinator,
            String workerId,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        await(start);
        return coordinator.dispatchNext(workerId);
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
