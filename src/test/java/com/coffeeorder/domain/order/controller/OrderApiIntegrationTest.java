package com.coffeeorder.domain.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.idempotency.service.IdempotencyRequestWriter;
import com.coffeeorder.domain.order.service.CreateOrderCommand;
import com.coffeeorder.domain.order.service.CreateOrderResult;
import com.coffeeorder.domain.order.service.OrderFacade;
import com.coffeeorder.domain.outbox.entity.OutboxEvent;
import com.coffeeorder.domain.outbox.repository.OutboxEventRepository;
import com.coffeeorder.global.observability.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OrderApiIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderFacade orderFacade;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private IdempotencyRequestWriter idempotencyRequestWriter;
    @MockitoSpyBean private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void resetData() {
        reset(idempotencyRequestWriter, outboxEventRepository);
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update(
                "UPDATE point_wallets SET balance = 10000, updated_at = UTC_TIMESTAMP(6)");
        jdbcTemplate.update("UPDATE menus SET status = 'ACTIVE' WHERE id <> 4");
        jdbcTemplate.update("UPDATE menus SET status = 'INACTIVE' WHERE id = 4");
    }

    @Test
    void 주문_성공은_모든_기록을_한_건씩_원자적으로_만들고_snapshot을_반환한다() throws Exception {
        String responseBody =
                mockMvc.perform(order("order-success", "{\"userId\":10,\"menuId\":2}"))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotency-Replayed", "false"))
                        .andExpect(jsonPath("$.orderId").isNumber())
                        .andExpect(jsonPath("$.userId").value(10))
                        .andExpect(jsonPath("$.menu.menuId").value(2))
                        .andExpect(jsonPath("$.menu.name").value("카페라떼"))
                        .andExpect(jsonPath("$.unitPrice").value(5000))
                        .andExpect(jsonPath("$.quantity").value(1))
                        .andExpect(jsonPath("$.paidAmount").value(5000))
                        .andExpect(jsonPath("$.remainingPointBalance").value(5000))
                        .andExpect(jsonPath("$.status").value("PAID"))
                        .andExpect(jsonPath("$.paidAt").isString())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(balance()).isEqualTo(5000);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("point_transactions")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_requests")).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT order_id FROM point_transactions", Long.class))
                .isEqualTo(jdbcTemplate.queryForObject("SELECT id FROM orders", Long.class));
        Instant responsePaidAt =
                Instant.parse(objectMapper.readTree(responseBody).path("paidAt").asText());
        Instant outboxOccurredAt =
                Instant.parse(
                        jdbcTemplate.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.occurredAt')) FROM outbox_events",
                                String.class));
        Instant databasePaidAt =
                jdbcTemplate
                        .queryForObject("SELECT paid_at FROM orders", Timestamp.class)
                        .toInstant();

        assertThat(responsePaidAt).isEqualTo(databasePaidAt);
        assertThat(outboxOccurredAt).isEqualTo(databasePaidAt);
        assertThat(databasePaidAt.getNano() % 1_000).isZero();
    }

    @Test
    void 같은_키_같은_요청은_최초_snapshot을_재생하고_추가_효과가_없다() {
        CreateOrderResult first = perform("same-order", 2);
        jdbcTemplate.update("UPDATE point_wallets SET balance = 9000 WHERE user_id = 10");
        CreateOrderResult replay = perform("same-order", 2);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.responseBody()).isEqualTo(first.responseBody());
        assertThat(balance()).isEqualTo(9000);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("point_transactions")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void 같은_키_다른_메뉴는_409이다() throws Exception {
        perform("reused-order", 1);
        mockMvc.perform(order("reused-order", "{\"userId\":10,\"menuId\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 메뉴_없음_비활성_잔액부족은_오류_snapshot만_완료한다() throws Exception {
        assertDeterministicError("menu-missing", 999, 404, "MENU_NOT_FOUND");
        assertDeterministicError("menu-inactive", 4, 409, "MENU_NOT_ORDERABLE");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 100 WHERE user_id = 10");
        assertDeterministicError("points-short", 2, 409, "INSUFFICIENT_POINTS");

        assertThat(count("orders")).isZero();
        assertThat(count("point_transactions")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(count("idempotency_requests")).isEqualTo(3);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM idempotency_requests WHERE status = 'COMPLETED' AND response_body NOT LIKE '%traceId%' AND response_body NOT LIKE '%timestamp%'",
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void 없는_사용자는_멱등_기록도_남기지_않는다() throws Exception {
        mockMvc.perform(order("missing-user", "{\"userId\":999,\"menuId\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertThat(count("idempotency_requests")).isZero();
    }

    @Test
    void outbox_저장_실패는_주문_차감_원장_멱등을_모두_롤백한다() {
        doThrow(new DataIntegrityViolationException("forced outbox failure"))
                .when(outboxEventRepository)
                .save(any(OutboxEvent.class));

        assertThatThrownBy(() -> perform("outbox-failure", 2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("forced outbox failure");
        assertNoEffects();
    }

    @Test
    void completed_flush_실패는_확정_성공_로그_없이_롤백되고_재시도는_한_번만_쓴다(CapturedOutput output) {
        doThrow(new DataIntegrityViolationException("forced completed failure"))
                .when(idempotencyRequestWriter)
                .flushCompleted(any());

        assertThatThrownBy(() -> perform("completed-failure", 2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("forced completed failure");
        assertNoEffects();
        assertThat(output)
                .contains("order_payment_attempted", "outbox_event_record_attempted")
                .doesNotContain("order_paid ", "outbox_event_recorded ");

        reset(idempotencyRequestWriter, outboxEventRepository);
        assertThat(perform("completed-failure", 2).status()).isEqualTo(201);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("point_transactions")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_requests")).isEqualTo(1);
    }

    @Test
    void 가격_등_알_수_없는_필드는_400이고_효과가_없다() throws Exception {
        mockMvc.perform(order("unknown-price", "{\"userId\":10,\"menuId\":2,\"price\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertNoEffects();
    }

    @Test
    void 실제_지갑_락_timeout은_503이고_멱등과_도메인_효과를_남기지_않는다() throws Exception {
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock =
                    lockHolder.prepareStatement(
                            "SELECT user_id FROM point_wallets WHERE user_id = 10 FOR UPDATE")) {
                try (ResultSet resultSet = lock.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                }
            }

            mockMvc.perform(order("order-lock-timeout", "{\"userId\":10,\"menuId\":2}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("CONCURRENCY_TIMEOUT"));

            assertNoEffects();
            lockHolder.rollback();
        }

        mockMvc.perform(order("order-lock-timeout", "{\"userId\":10,\"menuId\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("point_transactions")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_requests")).isEqualTo(1);
    }

    private void assertDeterministicError(String key, long menuId, int status, String code)
            throws Exception {
        MvcResult first =
                mockMvc.perform(order(key, "{\"userId\":10,\"menuId\":" + menuId + "}"))
                        .andExpect(status().is(status))
                        .andExpect(header().string("Idempotency-Replayed", "false"))
                        .andExpect(jsonPath("$.code").value(code))
                        .andReturn();
        MvcResult replay =
                mockMvc.perform(order(key, "{\"userId\":10,\"menuId\":" + menuId + "}"))
                        .andExpect(status().is(status))
                        .andExpect(header().string("Idempotency-Replayed", "true"))
                        .andExpect(jsonPath("$.code").value(code))
                        .andReturn();

        var firstBody = objectMapper.readTree(first.getResponse().getContentAsByteArray());
        var replayBody = objectMapper.readTree(replay.getResponse().getContentAsByteArray());
        String firstTraceId = firstBody.path("traceId").asText();
        String replayTraceId = replayBody.path("traceId").asText();

        assertThat(firstTraceId)
                .isNotBlank()
                .isEqualTo(first.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER));
        assertThat(replayTraceId)
                .isNotBlank()
                .isEqualTo(replay.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isNotEqualTo(firstTraceId);
        assertThat(replayBody.path("message").asText())
                .isEqualTo(firstBody.path("message").asText());
        assertThat(Instant.parse(replayBody.path("timestamp").asText()))
                .isNotEqualTo(Instant.parse(firstBody.path("timestamp").asText()));
    }

    private CreateOrderResult perform(String key, long menuId) {
        return orderFacade.create(
                new CreateOrderCommand(10, menuId, key),
                Instant.parse("2026-07-11T00:00:00Z"),
                "test-trace");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder order(
            String key, String body) {
        return post("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private long balance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM point_wallets WHERE user_id = 10", Long.class);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertNoEffects() {
        assertThat(balance()).isEqualTo(10000);
        assertThat(count("orders")).isZero();
        assertThat(count("point_transactions")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(count("idempotency_requests")).isZero();
    }
}
