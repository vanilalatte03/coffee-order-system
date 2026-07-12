package com.coffeeorder.domain.point.controller;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.idempotency.service.IdempotencyRequestWriter;
import com.coffeeorder.domain.point.service.ChargePointsCommand;
import com.coffeeorder.domain.point.service.ChargePointsResult;
import com.coffeeorder.domain.point.service.PointFacade;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PointChargeApiIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PointFacade pointFacade;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoSpyBean private IdempotencyRequestWriter idempotencyRequestWriter;

    @BeforeEach
    void resetData() {
        reset(idempotencyRequestWriter);
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 0, updated_at = UTC_TIMESTAMP(6)");
    }

    @Test
    void 정상_충전은_201과_원장_및_재생_헤더를_반환한다() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "charge-success")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.pointTransactionId").isNumber())
                .andExpect(jsonPath("$.userId").value(10))
                .andExpect(jsonPath("$.chargedAmount").value(10000))
                .andExpect(jsonPath("$.balance").value(10000))
                .andExpect(jsonPath("$.chargedAt").isString());

        assertThat(balanceOf(10)).isEqualTo(10000);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(idempotencyCount()).isEqualTo(1);

        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "charge-success")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.balance").value(10000));
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void 같은_키_재생은_후속_잔액과_무관하게_최초_body를_반환한다() throws Exception {
        String first = performCharge("replay-key", 100).responseBody();
        jdbcTemplate.update("UPDATE point_wallets SET balance = 900 WHERE user_id = 10");

        var replay = performCharge("replay-key", 100);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.responseBody()).isEqualTo(first);
        assertThat(balanceOf(10)).isEqualTo(900);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void 같은_키의_다른_금액은_409이다() throws Exception {
        performCharge("reused-key", 100);

        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "reused-key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":200}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 없는_사용자는_404이고_어떤_기록도_남지_않는다() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users/9999/points/charges")
                                .header("Idempotency-Key", "missing-user")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(ledgerCount()).isZero();
        assertThat(idempotencyCount()).isZero();
    }

    @Test
    void 오버플로는_안정_payload를_저장하고_재생한다() throws Exception {
        jdbcTemplate.update(
                "UPDATE point_wallets SET balance = ? WHERE user_id = 10", Long.MAX_VALUE);

        var first = performCharge("overflow-key", 1);
        var replay = performCharge("overflow-key", 1);

        assertThat(first.status()).isEqualTo(422);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.status()).isEqualTo(422);
        assertThat(replay.replayed()).isTrue();
        assertThat(first.responseBody()).contains("POINT_BALANCE_OVERFLOW", "trace-1");
        assertThat(replay.responseBody())
                .contains("POINT_BALANCE_OVERFLOW", "trace-2")
                .doesNotContain("trace-1");
        assertThat(balanceOf(10)).isEqualTo(Long.MAX_VALUE);
        assertThat(ledgerCount()).isZero();
        assertThat(idempotencyCount()).isEqualTo(1);
        String storedBody =
                jdbcTemplate.queryForObject(
                        "SELECT response_body FROM idempotency_requests", String.class);
        assertThat(storedBody).doesNotContain("timestamp", "traceId");
    }

    @Test
    void 같은_키_동시_20건은_충전과_원장을_한_번만_만든다() throws Exception {
        int requestCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CyclicBarrier barrier = new CyclicBarrier(requestCount);
        List<Future<ChargePointsResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                int requestIndex = index;
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await(10, SECONDS);
                                    return pointFacade.charge(
                                            new ChargePointsCommand(10, 100, "concurrent-20"),
                                            Instant.parse("2026-07-11T00:00:00Z"),
                                            "trace-" + requestIndex);
                                }));
            }
            List<ChargePointsResult> results = new ArrayList<>();
            for (Future<ChargePointsResult> future : futures) {
                results.add(future.get(30, SECONDS));
            }

            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(ChargePointsResult::replayed).hasSize(19);
            assertThat(results)
                    .extracting(ChargePointsResult::responseBody)
                    .containsOnly(results.get(0).responseBody());
            assertThat(balanceOf(10)).isEqualTo(100);
            assertThat(ledgerCount()).isEqualTo(1);
            assertThat(idempotencyCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 완료_snapshot_flush_실패는_전체_롤백되고_재시도는_한_번만_충전한다() throws Exception {
        doThrow(new DataIntegrityViolationException("forced completed flush failure"))
                .when(idempotencyRequestWriter)
                .flushCompleted(any());

        assertThatThrownBy(() -> performCharge("flush-failure", 100))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("forced completed flush failure");
        assertThat(balanceOf(10)).isZero();
        assertThat(ledgerCount()).isZero();
        assertThat(idempotencyCount()).isZero();

        reset(idempotencyRequestWriter);
        var retry = performCharge("flush-failure", 100);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(balanceOf(10)).isEqualTo(100);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    @Test
    void 실제_지갑_락_timeout은_503이고_같은_키_재시도는_정확히_한_번_충전한다() throws Exception {
        double timeoutBefore = walletLockFailureCount("lock_timeout");
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock =
                    lockHolder.prepareStatement(
                            "SELECT user_id FROM point_wallets WHERE user_id = ? FOR UPDATE")) {
                lock.setLong(1, 10);
                try (ResultSet resultSet = lock.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                }
            }

            mockMvc.perform(
                            post("/api/v1/users/10/points/charges")
                                    .header("Idempotency-Key", "lock-timeout-retry")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"amount\":100}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("CONCURRENCY_TIMEOUT"));

            assertThat(walletLockFailureCount("lock_timeout") - timeoutBefore).isEqualTo(1);

            assertThat(balanceOf(10)).isZero();
            assertThat(ledgerCount()).isZero();
            assertThat(idempotencyCount()).isZero();
            lockHolder.rollback();
        }

        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "lock-timeout-retry")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.balance").value(100));

        assertThat(balanceOf(10)).isEqualTo(100);
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(idempotencyCount()).isEqualTo(1);
    }

    private double walletLockFailureCount(String type) {
        var counter =
                meterRegistry
                        .find("coffee.wallet.lock.failures")
                        .tags("operation", "charge", "type", type)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 잘못된_header_path_body는_400이고_멱등_기록이_없다() throws Exception {
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "invalid key")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mockMvc.perform(
                        post("/api/v1/users/0/points/charges")
                                .header("Idempotency-Key", "bad-path")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "bad-body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(
                        post("/api/v1/users/10/points/charges")
                                .header("Idempotency-Key", "fraction-body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(idempotencyCount()).isZero();
    }

    private ChargePointsResult performCharge(String key, long amount) {
        int traceSequence = idempotencyCount() + 1;
        return pointFacade.charge(
                new ChargePointsCommand(10, amount, key),
                Instant.parse("2026-07-11T00:00:0" + traceSequence + "Z"),
                "trace-" + traceSequence);
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
}
