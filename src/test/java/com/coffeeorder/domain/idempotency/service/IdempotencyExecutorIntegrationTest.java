package com.coffeeorder.domain.idempotency.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.repository.IdempotencyRequestRepository;
import com.coffeeorder.domain.point.service.PointWriteService;
import com.coffeeorder.domain.user.service.UserNotFoundException;
import com.coffeeorder.domain.user.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
class IdempotencyExecutorIntegrationTest extends MySqlIntegrationTestSupport {

    private static final CanonicalPayload CHARGE_100 =
            CanonicalPayload.fromJson("{\"amount\":100,\"userId\":10}");
    private static final CanonicalPayload CHARGE_200 =
            CanonicalPayload.fromJson("{\"amount\":200,\"userId\":10}");

    @Autowired private IdempotencyExecutor idempotencyExecutor;
    @Autowired private PointWriteService pointWriteService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private IdempotencyRequestWriter idempotencyRequestWriter;
    @MockitoSpyBean private IdempotencyRequestRepository idempotencyRequestRepository;

    @BeforeEach
    void resetData() {
        reset(idempotencyRequestWriter);
        reset(idempotencyRequestRepository);
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 0, updated_at = UTC_TIMESTAMP(6)");
    }

    @Test
    void 같은_범위와_hash는_최초_성공_snapshot을_재생한다() {
        AtomicInteger executions = new AtomicInteger();

        IdempotencyExecutionResult first =
                executeCharge(
                        10,
                        "same-key",
                        CHARGE_100,
                        () -> {
                            executions.incrementAndGet();
                            long balance = pointWriteService.charge(10, 100);
                            return success(balance);
                        });
        pointWriteService.charge(10, 899);
        IdempotencyExecutionResult replay =
                executeCharge(
                        10,
                        "same-key",
                        CHARGE_100,
                        () -> {
                            executions.incrementAndGet();
                            return success(999);
                        });

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.snapshot()).isEqualTo(first.snapshot());
        assertThat(executions).hasValue(1);
        assertThat(balanceOf(10)).isEqualTo(999);
        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void 같은_범위의_다른_hash는_키_재사용_충돌이다() {
        executeCharge(10, "reused-key", CHARGE_100, () -> success(100));

        assertThatThrownBy(
                        () ->
                                executeCharge(
                                        10,
                                        "reused-key",
                                        CanonicalPayload.fromJson("{\"amount\":200,\"userId\":10}"),
                                        () -> success(200)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void operation과_사용자가_다르면_같은_키를_독립적으로_사용한다() {
        IdempotencyExecutionResult userTen =
                executeCharge(10, "shared-key", CHARGE_100, () -> success(10));
        IdempotencyExecutionResult anotherOperation =
                idempotencyExecutor.execute(
                        10,
                        IdempotencyOperation.ORDER_CREATE,
                        "shared-key",
                        CHARGE_100,
                        () -> userService.validateExists(10),
                        () -> success(20));
        IdempotencyExecutionResult anotherUser =
                idempotencyExecutor.execute(
                        20,
                        IdempotencyOperation.POINT_CHARGE,
                        "shared-key",
                        CanonicalPayload.fromJson("{\"amount\":100,\"userId\":20}"),
                        () -> userService.validateExists(20),
                        () -> success(30));
        IdempotencyExecutionResult userTenReplay =
                executeCharge(10, "shared-key", CHARGE_100, () -> success(999));
        IdempotencyExecutionResult userTwentyReplay =
                idempotencyExecutor.execute(
                        20,
                        IdempotencyOperation.POINT_CHARGE,
                        "shared-key",
                        CanonicalPayload.fromJson("{\"amount\":100,\"userId\":20}"),
                        () -> userService.validateExists(20),
                        () -> success(999));

        assertThat(List.of(userTen, anotherOperation, anotherUser))
                .allMatch(result -> !result.replayed());
        assertThat(userTenReplay.replayed()).isTrue();
        assertThat(userTenReplay.snapshot()).isEqualTo(userTen.snapshot());
        assertThat(userTwentyReplay.replayed()).isTrue();
        assertThat(userTwentyReplay.snapshot()).isEqualTo(anotherUser.snapshot());
        assertThat(idempotencyCount()).isEqualTo(3);
    }

    @Test
    void 대소문자만_다른_키는_독립적으로_실행한다() {
        IdempotencyExecutionResult upperCaseKey =
                executeCharge(
                        10,
                        "Case-Key",
                        CHARGE_100,
                        () -> success(pointWriteService.charge(10, 100)));
        IdempotencyExecutionResult lowerCaseKey =
                executeCharge(
                        10,
                        "case-key",
                        CHARGE_200,
                        () -> success(pointWriteService.charge(10, 200)));

        assertThat(upperCaseKey.replayed()).isFalse();
        assertThat(lowerCaseKey.replayed()).isFalse();
        assertThat(balanceOf(10)).isEqualTo(300);
        assertThat(ledgerCount()).isEqualTo(2);
        assertThat(idempotencyCount()).isEqualTo(2);
    }

    @Test
    void 멱등_키는_허용된_문자_1자부터_128자까지만_받는다() {
        String maximumLengthKey = "a".repeat(128);

        IdempotencyExecutionResult valid =
                executeCharge(10, maximumLengthKey, CHARGE_100, () -> success(100));

        assertThat(valid.replayed()).isFalse();
        assertThatThrownBy(() -> executeCharge(10, "invalid key", CHARGE_100, () -> success(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executeCharge(10, "a".repeat(129), CHARGE_100, () -> success(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void 사용자_사전_검증_실패에는_멱등_행이_남지_않는다() {
        assertThatThrownBy(
                        () ->
                                idempotencyExecutor.execute(
                                        9999,
                                        IdempotencyOperation.POINT_CHARGE,
                                        "missing-user",
                                        CanonicalPayload.fromJson(
                                                "{\"amount\":100,\"userId\":9999}"),
                                        () -> userService.validateExists(9999),
                                        () -> success(100)))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void 일시적_예외는_업무_변경과_PROCESSING_행을_함께_롤백한다() {
        assertThatThrownBy(
                        () ->
                                executeCharge(
                                        10,
                                        "transient-failure",
                                        CHARGE_100,
                                        () -> {
                                            pointWriteService.charge(10, 100);
                                            throw new TransientTestException();
                                        }))
                .isInstanceOf(TransientTestException.class);

        assertThat(balanceOf(10)).isZero();
        assertThat(ledgerCount()).isZero();
        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void 결정적_오류는_업무_쓰기_없이_안정_payload만_재생한다() {
        IdempotencyResponseSnapshot error =
                IdempotencyResponseSnapshot.deterministicError(
                        409, "{\"code\":\"INSUFFICIENT_POINTS\",\"message\":\"포인트가 부족합니다.\"}");

        IdempotencyExecutionResult first =
                executeCharge(10, "business-error", CHARGE_100, () -> error);
        IdempotencyExecutionResult replay =
                executeCharge(10, "business-error", CHARGE_100, () -> success(100));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.snapshot().storedBody()).doesNotContain("traceId", "timestamp");
        assertThat(
                        first.snapshot()
                                .responseBody(Instant.parse("2026-07-11T01:00:00Z"), "first-trace"))
                .contains("first-trace", "2026-07-11T01:00:00Z");
        assertThat(
                        replay.snapshot()
                                .responseBody(
                                        Instant.parse("2026-07-11T02:00:00Z"), "replay-trace"))
                .contains("replay-trace", "2026-07-11T02:00:00Z")
                .doesNotContain("first-trace");
        assertThat(ledgerCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"error\"", "42", "null"})
    void 객체가_아닌_결정적_오류_payload는_업무와_PROCESSING을_롤백한다(String invalidBody) {
        assertThatThrownBy(
                        () ->
                                executeCharge(
                                        10,
                                        "invalid-error-body",
                                        CHARGE_100,
                                        () -> {
                                            pointWriteService.charge(10, 100);
                                            return IdempotencyResponseSnapshot.deterministicError(
                                                    409, invalidBody);
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a JSON object");

        assertThat(balanceOf(10)).isZero();
        assertThat(ledgerCount()).isZero();
        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void COMPLETED_snapshot_flush_실패는_업무와_PROCESSING을_모두_롤백한다() {
        doThrow(new DataIntegrityViolationException("forced completed snapshot flush failure"))
                .when(idempotencyRequestWriter)
                .flushCompleted(any());

        assertThatThrownBy(
                        () ->
                                executeCharge(
                                        10,
                                        "snapshot-failure",
                                        CHARGE_100,
                                        () -> success(pointWriteService.charge(10, 100))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("forced completed snapshot flush failure");

        assertThat(balanceOf(10)).isZero();
        assertThat(ledgerCount()).isZero();
        assertThat(idempotencyCount()).isZero();

        reset(idempotencyRequestWriter);
        IdempotencyExecutionResult retry =
                executeCharge(
                        10,
                        "snapshot-failure",
                        CHARGE_100,
                        () -> success(pointWriteService.charge(10, 100)));

        assertThat(retry.replayed()).isFalse();
        assertThat(balanceOf(10)).isEqualTo(100);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void 동시_선점은_DB_유니크_승자_한_건만_업무를_실행한다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        CyclicBarrier processingFlushBarrier = new CyclicBarrier(2);
        AtomicInteger executions = new AtomicInteger();

        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            processingFlushBarrier.await(5, SECONDS);
                            return invocation.callRealMethod();
                        })
                .when(idempotencyRequestWriter)
                .flushProcessing(any());

        try {
            Future<IdempotencyExecutionResult> first =
                    executor.submit(
                            () -> {
                                startBarrier.await(5, SECONDS);
                                return executeCharge(
                                        10,
                                        "concurrent-key",
                                        CHARGE_100,
                                        () -> {
                                            executions.incrementAndGet();
                                            return success(pointWriteService.charge(10, 100));
                                        });
                            });
            Future<IdempotencyExecutionResult> second =
                    executor.submit(
                            () -> {
                                startBarrier.await(5, SECONDS);
                                return executeCharge(
                                        10,
                                        "concurrent-key",
                                        CHARGE_100,
                                        () -> {
                                            executions.incrementAndGet();
                                            return success(pointWriteService.charge(10, 100));
                                        });
                            });

            List<IdempotencyExecutionResult> results =
                    List.of(first.get(10, SECONDS), second.get(10, SECONDS));
            assertThat(results).anyMatch(result -> !result.replayed());
            assertThat(results).anyMatch(IdempotencyExecutionResult::replayed);
            assertThat(results.get(0).snapshot()).isEqualTo(results.get(1).snapshot());
            assertThat(executions).hasValue(1);
            assertThat(balanceOf(10)).isEqualTo(100);
            assertThat(ledgerCount()).isEqualTo(1);
            assertThat(idempotencyCount()).isEqualTo(1);
            verify(idempotencyRequestRepository, times(3))
                    .findByUserIdAndOperationAndIdempotencyKey(
                            10, IdempotencyOperation.POINT_CHARGE, "concurrent-key");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 동시_같은_키의_다른_hash는_승자만_실행하고_패자를_거절한다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        CyclicBarrier processingFlushBarrier = new CyclicBarrier(2);
        AtomicInteger executions = new AtomicInteger();

        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            processingFlushBarrier.await(5, SECONDS);
                            return invocation.callRealMethod();
                        })
                .when(idempotencyRequestWriter)
                .flushProcessing(any());

        try {
            Future<IdempotencyExecutionResult> first =
                    executor.submit(
                            () -> {
                                startBarrier.await(5, SECONDS);
                                return executeCharge(
                                        10,
                                        "concurrent-reused-key",
                                        CHARGE_100,
                                        () -> {
                                            executions.incrementAndGet();
                                            return success(pointWriteService.charge(10, 100));
                                        });
                            });
            Future<IdempotencyExecutionResult> second =
                    executor.submit(
                            () -> {
                                startBarrier.await(5, SECONDS);
                                return executeCharge(
                                        10,
                                        "concurrent-reused-key",
                                        CHARGE_200,
                                        () -> {
                                            executions.incrementAndGet();
                                            return success(pointWriteService.charge(10, 200));
                                        });
                            });

            List<Object> outcomes = List.of(resultOrFailure(first), resultOrFailure(second));
            assertThat(
                            outcomes.stream()
                                    .filter(IdempotencyExecutionResult.class::isInstance)
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            outcomes.stream()
                                    .filter(IdempotencyKeyReusedException.class::isInstance)
                                    .count())
                    .isEqualTo(1);
            assertThat(executions).hasValue(1);
            assertThat(balanceOf(10)).isIn(100L, 200L);
            assertThat(ledgerCount()).isEqualTo(1);
            assertThat(idempotencyCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private IdempotencyExecutionResult executeCharge(
            long userId, String key, CanonicalPayload payload, IdempotencyWork work) {
        return idempotencyExecutor.execute(
                userId,
                IdempotencyOperation.POINT_CHARGE,
                key,
                payload,
                () -> userService.validateExists(userId),
                work);
    }

    private static IdempotencyResponseSnapshot success(long balance) {
        return IdempotencyResponseSnapshot.success(
                201, "{\"balance\":" + balance + ",\"chargedAt\":\"2026-07-11T00:00:00Z\"}");
    }

    private static Object resultOrFailure(Future<IdempotencyExecutionResult> future)
            throws Exception {
        try {
            return future.get(10, SECONDS);
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private long balanceOf(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM point_wallets WHERE user_id = ?", Long.class, userId);
    }

    private int ledgerCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_transactions", Integer.class);
    }

    private int idempotencyCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_requests", Integer.class);
    }

    private static final class TransientTestException extends RuntimeException {}
}
