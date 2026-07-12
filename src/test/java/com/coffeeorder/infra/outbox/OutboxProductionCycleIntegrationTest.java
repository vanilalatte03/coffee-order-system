package com.coffeeorder.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.domain.outbox.service.OrderEventPublishResult;
import com.coffeeorder.domain.outbox.service.OrderEventPublisher;
import com.coffeeorder.domain.outbox.service.OutboxBackoffPolicy;
import com.coffeeorder.domain.outbox.service.OutboxDeliveryCoordinator;
import com.coffeeorder.domain.outbox.service.OutboxDeliveryWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@DisplayName("아웃박스 운영 주기 통합 테스트")
class OutboxProductionCycleIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-07-11T12:00:00.123456Z");

    @Autowired private OutboxDeliveryRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private OrderEventHttpStub httpStub;

    @BeforeEach
    void setUp() {
        httpStub = new OrderEventHttpStub();
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 10000 WHERE user_id = 10");
    }

    @AfterEach
    void cleanUp() {
        httpStub.close();
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @DisplayName("운영 주기 11회는 자동 시도 한도를 소진한다")
    @Test
    void elevenProductionCyclesExhaustTheAutomaticAttemptLimit() {
        String eventId = insertPending();
        httpStub.respondWith(503);
        AdjustableClock clock = new AdjustableClock(NOW);
        OutboxDeliveryWorker worker = worker(clock, adapter(), "retry-worker");

        for (int expectedAttempt = 1; expectedAttempt <= 11; expectedAttempt++) {
            assertThat(worker.runOneCycle()).isEqualTo(1);
            assertThat(attemptCount(eventId)).isEqualTo(expectedAttempt);
            if (expectedAttempt < 11) {
                clock.set(nextAttemptAt(eventId));
            }
        }

        assertThat(status(eventId)).isEqualTo("FAILED");
        assertThat(httpStub.requests()).hasSize(11);
    }

    @DisplayName("수신자 성공 뒤 중복 전달은 이벤트 ID별로 한 번만 적용된다")
    @Test
    void duplicateDeliveryAfterReceiverSuccessIsAppliedOnceByEventId() {
        String eventId = insertPending();
        AdjustableClock clock = new AdjustableClock(NOW);
        OrderEventPublisher http = adapter();
        AtomicBoolean crashAfterFirstSuccess = new AtomicBoolean(true);
        OrderEventPublisher crashWindow =
                event -> {
                    OrderEventPublishResult received = http.publish(event);
                    assertThat(received.type()).isEqualTo(OrderEventPublishResult.Type.SUCCESS);
                    if (crashAfterFirstSuccess.getAndSet(false)) {
                        throw new SimulatedProcessCrash();
                    }
                    return received;
                };
        OutboxDeliveryWorker worker = worker(clock, crashWindow, "duplicate-worker");

        assertThatThrownBy(worker::runOneCycle).isInstanceOf(SimulatedProcessCrash.class);
        String firstClaimToken = stringColumn(eventId, "claim_token");
        Instant firstLockedUntil = timestampColumn(eventId, "locked_until");
        assertThat(status(eventId)).isEqualTo("PROCESSING");
        assertThat(attemptCount(eventId)).isEqualTo(1);
        assertThat(firstClaimToken).isNotBlank();
        assertThat(stringColumn(eventId, "locked_by")).isEqualTo("duplicate-worker");
        assertThat(firstLockedUntil).isEqualTo(NOW.plusSeconds(30));

        clock.set(firstLockedUntil.plusNanos(1_000));
        assertThat(worker.runOneCycle()).isEqualTo(1);

        assertThat(httpStub.requests())
                .extracting(OrderEventHttpStub.Request::eventId)
                .containsExactly(eventId, eventId);
        assertThat(httpStub.appliedCount()).isEqualTo(1);
        assertThat(attemptCount(eventId)).isEqualTo(2);
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @DisplayName("여러 운영 작업자는 이벤트 하나를 선점 하나로 완료한다")
    @Test
    void multipleProductionWorkersCompleteOneEventWithOneClaim() throws Exception {
        String eventId = insertPending();
        AdjustableClock clock = new AdjustableClock(NOW);
        AtomicInteger publishes = new AtomicInteger();
        OrderEventPublisher publisher =
                event -> {
                    publishes.incrementAndGet();
                    return adapter().publish(event);
                };
        OutboxDeliveryWorker first = worker(clock, publisher, "worker-one");
        OutboxDeliveryWorker second = worker(clock, publisher, "worker-two");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> one = executor.submit(() -> runWhenReady(first, ready, start));
            Future<Integer> two = executor.submit(() -> runWhenReady(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS) + two.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(publishes).hasValue(1);
        assertThat(attemptCount(eventId)).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @DisplayName("차단된 HTTP 응답은 DB 트랜잭션, 지갑, 아웃박스 행 잠금을 유지하지 않는다")
    @Test
    void blockedHttpResponseKeepsNoDatabaseTransactionOrWalletAndOutboxRowLock() throws Exception {
        String eventId = insertPending();
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        httpStub.blockResponses(requestEntered, releaseResponse);
        OutboxDeliveryWorker worker = worker(new AdjustableClock(NOW), adapter(), "lock-probe");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> delivery = executor.submit(worker::runOneCycle);
            assertThat(requestEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> databaseWork =
                    executor.submit(
                            () -> {
                                int wallet =
                                        jdbcTemplate.update(
                                                "UPDATE point_wallets SET balance = balance + 1 WHERE user_id = 10");
                                int outbox =
                                        jdbcTemplate.update(
                                                "UPDATE outbox_events SET last_error = 'lock probe' WHERE event_id = ?",
                                                eventId);
                                return wallet + outbox;
                            });
            assertThat(databaseWork.get(2, TimeUnit.SECONDS)).isEqualTo(2);
            releaseResponse.countDown();
            assertThat(delivery.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    private OrderEventPublisher adapter() {
        OutboxDeliveryProperties properties =
                new OutboxDeliveryProperties(
                        true,
                        httpStub.baseUrl(),
                        Duration.ofMillis(200),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(30),
                        "adapter-worker",
                        true);
        return new HttpOrderEventPublisherAdapter(properties, new SimpleMeterRegistry());
    }

    private OutboxDeliveryWorker worker(
            Clock clock, OrderEventPublisher publisher, String workerId) {
        OutboxDeliveryCoordinator coordinator =
                new OutboxDeliveryCoordinator(
                        repository,
                        publisher,
                        clock,
                        Duration.ofSeconds(30),
                        new OutboxBackoffPolicy(),
                        () -> 1.0,
                        transactionManager);
        return new OutboxDeliveryWorker(coordinator, workerId);
    }

    private String insertPending() {
        String eventId = UUID.randomUUID().toString();
        String payload =
                "{\"schemaVersion\":1,\"eventId\":\""
                        + eventId
                        + "\",\"eventType\":\"ORDER_PAID\",\"occurredAt\":\""
                        + NOW
                        + "\",\"orderId\":1001,\"userId\":10,\"menuId\":2,\"paymentAmount\":5000}";
        jdbcTemplate.update(
                """
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, schema_version, payload,
                    status, attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (?, 'ORDER', ?, 'ORDER_PAID', 1, ?, 'PENDING', 0, ?, ?, ?)
                """,
                eventId,
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1,
                payload,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        return eventId;
    }

    private int attemptCount(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE event_id = ?",
                Integer.class,
                eventId);
    }

    private String status(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
    }

    private Instant nextAttemptAt(String eventId) {
        return jdbcTemplate
                .queryForObject(
                        "SELECT next_attempt_at FROM outbox_events WHERE event_id = ?",
                        Timestamp.class,
                        eventId)
                .toInstant();
    }

    private String stringColumn(String eventId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM outbox_events WHERE event_id = ?",
                String.class,
                eventId);
    }

    private Instant timestampColumn(String eventId, String column) {
        return jdbcTemplate
                .queryForObject(
                        "SELECT " + column + " FROM outbox_events WHERE event_id = ?",
                        Timestamp.class,
                        eventId)
                .toInstant();
    }

    private static int runWhenReady(
            OutboxDeliveryWorker worker, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return worker.runOneCycle();
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

    private static final class AdjustableClock extends Clock {

        private volatile Instant instant;

        private AdjustableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class SimulatedProcessCrash extends Error {}
}
