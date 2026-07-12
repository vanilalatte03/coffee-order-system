package com.coffeeorder.acceptance;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.CoffeeOrderSystemApplication;
import com.coffeeorder.MySqlIntegrationTestSupport;
import com.coffeeorder.domain.order.service.CreateOrderCommand;
import com.coffeeorder.domain.order.service.CreateOrderResult;
import com.coffeeorder.domain.order.service.OrderFacade;
import com.coffeeorder.domain.point.service.ChargePointsCommand;
import com.coffeeorder.domain.point.service.ChargePointsResult;
import com.coffeeorder.domain.point.service.PointFacade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhaseOneAcceptanceIntegrationTest extends MySqlIntegrationTestSupport {

    private static final Instant FIRST_RESPONSE_TIME = Instant.parse("2026-07-12T01:00:00.123456Z");
    private static final Instant REPLAY_RESPONSE_TIME =
            Instant.parse("2026-07-12T01:00:01.654321Z");

    private ConfigurableApplicationContext firstContext;
    private ConfigurableApplicationContext secondContext;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    @BeforeAll
    void startIndependentApplicationContexts() {
        firstContext = startContext("phase-one-acceptance-a");
        secondContext = startContext("phase-one-acceptance-b");
        jdbcTemplate = firstContext.getBean(JdbcTemplate.class);
        objectMapper = firstContext.getBean(ObjectMapper.class);

        assertThat(firstContext).isNotSameAs(secondContext);
        assertThat(firstContext.getBean(javax.sql.DataSource.class))
                .isNotSameAs(secondContext.getBean(javax.sql.DataSource.class));
    }

    @AfterAll
    void closeIndependentApplicationContexts() {
        if (secondContext != null) {
            secondContext.close();
        }
        if (firstContext != null) {
            firstContext.close();
        }
    }

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_requests");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 0, updated_at = UTC_TIMESTAMP(6)");
        jdbcTemplate.update("UPDATE menus SET price = 4500, status = 'ACTIVE' WHERE id = 1");
        jdbcTemplate.update("UPDATE menus SET price = 5000, status = 'ACTIVE' WHERE id = 2");
        jdbcTemplate.update("UPDATE menus SET status = 'ACTIVE' WHERE id = 3");
        jdbcTemplate.update("UPDATE menus SET status = 'INACTIVE' WHERE id = 4");
    }

    @Test
    void 서로_다른_키의_동시_주문_20건은_잔액_범위에서_정확히_10건만_성공한다() throws Exception {
        jdbcTemplate.update("UPDATE menus SET price = 1000 WHERE id = 1");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 10000 WHERE user_id = 10");
        int requestCount = 20;
        CyclicBarrier barrier = new CyclicBarrier(requestCount);
        List<Future<CreateOrderResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            for (int index = 0; index < requestCount; index++) {
                int requestIndex = index;
                OrderFacade facade =
                        requestIndex % 2 == 0
                                ? firstContext.getBean(OrderFacade.class)
                                : secondContext.getBean(OrderFacade.class);
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await(10, SECONDS);
                                    return facade.create(
                                            new CreateOrderCommand(
                                                    10, 1, "twenty-orders-" + requestIndex),
                                            FIRST_RESPONSE_TIME,
                                            "trace-order-" + requestIndex);
                                }));
            }

            List<CreateOrderResult> results = awaitAll(futures, 30);
            assertThat(results).filteredOn(result -> result.status() == 201).hasSize(10);
            assertThat(results).filteredOn(result -> result.status() == 409).hasSize(10);
            assertThat(results)
                    .filteredOn(result -> result.status() == 409)
                    .allSatisfy(
                            result ->
                                    assertThat(result.responseBody())
                                            .contains("INSUFFICIENT_POINTS"));
        }

        assertThat(balance()).isZero();
        assertThat(count("orders")).isEqualTo(10);
        assertThat(countWhere("point_transactions", "type = 'PAYMENT'")).isEqualTo(10);
        assertThat(count("outbox_events")).isEqualTo(10);
        assertThat(count("idempotency_requests")).isEqualTo(20);
    }

    @Test
    void 독립_context의_같은_주문과_키_경쟁은_최초_결과만_한_번_만든다() throws Exception {
        jdbcTemplate.update("UPDATE point_wallets SET balance = 10000 WHERE user_id = 10");
        OrderFacade firstFacade = firstContext.getBean(OrderFacade.class);
        OrderFacade secondFacade = secondContext.getBean(OrderFacade.class);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CreateOrderResult> first =
                    executor.submit(
                            () -> {
                                barrier.await(10, SECONDS);
                                return firstFacade.create(
                                        new CreateOrderCommand(10, 2, "cross-context-order"),
                                        FIRST_RESPONSE_TIME,
                                        "trace-context-a");
                            });
            Future<CreateOrderResult> second =
                    executor.submit(
                            () -> {
                                barrier.await(10, SECONDS);
                                return secondFacade.create(
                                        new CreateOrderCommand(10, 2, "cross-context-order"),
                                        REPLAY_RESPONSE_TIME,
                                        "trace-context-b");
                            });

            List<CreateOrderResult> results =
                    List.of(first.get(20, SECONDS), second.get(20, SECONDS));
            assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(201));
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            assertThat(results).filteredOn(CreateOrderResult::replayed).hasSize(1);
            assertThat(results)
                    .extracting(CreateOrderResult::responseBody)
                    .containsOnly(results.getFirst().responseBody());
        }

        assertThat(balance()).isEqualTo(5000);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(countWhere("point_transactions", "type = 'PAYMENT'")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_requests")).isEqualTo(1);
    }

    @Test
    void 독립_context의_충전과_주문_경쟁은_지갑과_원장을_일관되게_직렬화한다() throws Exception {
        jdbcTemplate.update("UPDATE point_wallets SET balance = 5000 WHERE user_id = 10");
        PointFacade pointFacade = firstContext.getBean(PointFacade.class);
        OrderFacade orderFacade = secondContext.getBean(OrderFacade.class);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ChargePointsResult> charge =
                    executor.submit(
                            () -> {
                                barrier.await(10, SECONDS);
                                return pointFacade.charge(
                                        new ChargePointsCommand(10, 1000, "cross-context-charge"),
                                        FIRST_RESPONSE_TIME,
                                        "trace-charge");
                            });
            Future<CreateOrderResult> order =
                    executor.submit(
                            () -> {
                                barrier.await(10, SECONDS);
                                return orderFacade.create(
                                        new CreateOrderCommand(10, 2, "cross-context-payment"),
                                        FIRST_RESPONSE_TIME,
                                        "trace-payment");
                            });

            assertThat(charge.get(20, SECONDS).status()).isEqualTo(201);
            assertThat(order.get(20, SECONDS).status()).isEqualTo(201);
        }

        assertThat(balance()).isEqualTo(1000);
        assertThat(countWhere("point_transactions", "type = 'CHARGE' AND amount = 1000"))
                .isEqualTo(1);
        assertThat(countWhere("point_transactions", "type = 'PAYMENT' AND amount = 5000"))
                .isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_requests")).isEqualTo(2);
    }

    @Test
    void 비활성_메뉴_재전송은_안정_payload와_현재_요청_메타데이터를_반환한다() throws Exception {
        OrderFacade facade = firstContext.getBean(OrderFacade.class);
        CreateOrderCommand command = new CreateOrderCommand(10, 4, "inactive-menu-replay");

        CreateOrderResult first =
                facade.create(command, FIRST_RESPONSE_TIME, "trace-inactive-first");
        CreateOrderResult replay =
                facade.create(command, REPLAY_RESPONSE_TIME, "trace-inactive-replay");
        JsonNode firstBody = objectMapper.readTree(first.responseBody());
        JsonNode replayBody = objectMapper.readTree(replay.responseBody());

        assertThat(first.status()).isEqualTo(409);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.status()).isEqualTo(409);
        assertThat(replay.replayed()).isTrue();
        assertThat(replayBody.path("code").asText()).isEqualTo(firstBody.path("code").asText());
        assertThat(replayBody.path("message").asText())
                .isEqualTo(firstBody.path("message").asText());
        assertThat(firstBody.path("timestamp").asText()).isEqualTo(FIRST_RESPONSE_TIME.toString());
        assertThat(replayBody.path("timestamp").asText())
                .isEqualTo(REPLAY_RESPONSE_TIME.toString());
        assertThat(firstBody.path("traceId").asText()).isEqualTo("trace-inactive-first");
        assertThat(replayBody.path("traceId").asText()).isEqualTo("trace-inactive-replay");
        assertThat(count("orders")).isZero();
        assertThat(count("point_transactions")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(count("idempotency_requests")).isEqualTo(1);
    }

    private ConfigurableApplicationContext startContext(String applicationName) {
        return new SpringApplicationBuilder(CoffeeOrderSystemApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.application.name=" + applicationName,
                        "--spring.datasource.url=" + jdbcUrl(),
                        "--spring.datasource.username=" + mysql.getUsername(),
                        "--spring.datasource.password=" + mysql.getPassword(),
                        "--spring.jmx.enabled=false",
                        "--spring.main.banner-mode=off",
                        "--outbox.delivery.enabled=false");
    }

    private String jdbcUrl() {
        String separator = mysql.getJdbcUrl().contains("?") ? "&" : "?";
        return mysql.getJdbcUrl()
                + separator
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    private static <T> List<T> awaitAll(List<Future<T>> futures, int timeoutSeconds)
            throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(timeoutSeconds, SECONDS));
        }
        return results;
    }

    private long balance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM point_wallets WHERE user_id = 10", Long.class);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String condition) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + condition, Integer.class);
    }
}
