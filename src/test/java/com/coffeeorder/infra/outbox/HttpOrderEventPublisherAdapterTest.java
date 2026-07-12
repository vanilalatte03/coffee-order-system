package com.coffeeorder.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.domain.outbox.service.ClaimedOrderEvent;
import com.coffeeorder.domain.outbox.service.OrderEventPublishResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("HTTP 주문 이벤트 발행 어댑터")
class HttpOrderEventPublisherAdapterTest {

    private static final String EVENT_ID = "7e8422d3-9638-4e40-a230-efbea89d8d4a";
    private static final String PAYLOAD =
            """
            {"schemaVersion":1,"eventId":"7e8422d3-9638-4e40-a230-efbea89d8d4a","eventType":"ORDER_PAID","occurredAt":"2026-07-10T04:35:00.456Z","orderId":1001,"userId":10,"menuId":2,"paymentAmount":5000}
            """
                    .trim();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @DisplayName("이벤트 헤더와 JSON 페이로드가 외부 계약과 일치한다")
    @Test
    void eventHeaderAndJsonPayloadMatchTheExternalContract() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server =
                start(
                        exchange -> {
                            header.set(exchange.getRequestHeaders().getFirst("X-Event-Id"));
                            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                            body.set(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            respond(exchange, 202);
                        });

        OrderEventPublishResult result =
                adapter(serverAddress(), Duration.ofSeconds(1)).publish(event());

        assertThat(result.type()).isEqualTo(OrderEventPublishResult.Type.SUCCESS);
        assertThat(header).hasValue(EVENT_ID);
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(body).hasValue(PAYLOAD);
    }

    @DisplayName("HTTP 상태를 계약에 따라 분류한다")
    @ParameterizedTest
    @CsvSource({
        "200,SUCCESS",
        "204,SUCCESS",
        "299,SUCCESS",
        "429,RETRYABLE_FAILURE",
        "500,RETRYABLE_FAILURE",
        "503,RETRYABLE_FAILURE",
        "400,PERMANENT_FAILURE",
        "404,PERMANENT_FAILURE"
    })
    void httpStatusesAreClassifiedByContract(int status, OrderEventPublishResult.Type expectedType)
            throws Exception {
        server = start(exchange -> respond(exchange, status));

        OrderEventPublishResult result =
                adapter(serverAddress(), Duration.ofSeconds(1)).publish(event());

        assertThat(result.type()).isEqualTo(expectedType);
        if (expectedType != OrderEventPublishResult.Type.SUCCESS) {
            assertThat(result.error()).isEqualTo("HTTP " + status);
        }
    }

    @DisplayName("읽기 시간 초과는 재시도 가능하고 오류에 페이로드를 노출하지 않는다")
    @Test
    void readTimeoutIsRetryableAndDoesNotExposePayloadInTheError() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server =
                start(
                        exchange -> {
                            requestEntered.countDown();
                            await(release);
                            respond(exchange, 200);
                        });

        OrderEventPublishResult result =
                adapter(serverAddress(), Duration.ofMillis(100)).publish(event());
        release.countDown();

        assertThat(requestEntered.getCount()).isZero();
        assertThat(result.type()).isEqualTo(OrderEventPublishResult.Type.RETRYABLE_FAILURE);
        assertThat(result.error()).contains("network error").doesNotContain(PAYLOAD);
    }

    @DisplayName("연결 실패는 재시도 가능하다")
    @Test
    void connectionFailureIsRetryable() throws Exception {
        server = start(exchange -> respond(exchange, 200));
        URI stoppedAddress = serverAddress();
        server.stop(0);
        server = null;

        OrderEventPublishResult result =
                adapter(stoppedAddress, Duration.ofMillis(200)).publish(event());

        assertThat(result.type()).isEqualTo(OrderEventPublishResult.Type.RETRYABLE_FAILURE);
        assertThat(result.error()).startsWith("network error");
    }

    private HttpOrderEventPublisherAdapter adapter(URI baseUrl, Duration readTimeout) {
        OutboxDeliveryProperties properties =
                new OutboxDeliveryProperties(
                        true,
                        baseUrl,
                        Duration.ofMillis(200),
                        readTimeout,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(30),
                        "test-worker",
                        true);
        return new HttpOrderEventPublisherAdapter(properties, new SimpleMeterRegistry());
    }

    private ClaimedOrderEvent event() {
        return new ClaimedOrderEvent(EVENT_ID, PAYLOAD, 1, "claim-token");
    }

    private HttpServer start(ExchangeHandler handler) throws IOException {
        HttpServer started = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        started.createContext(
                "/api/v1/order-events",
                exchange -> {
                    try {
                        handler.handle(exchange);
                    } finally {
                        exchange.close();
                    }
                });
        started.start();
        return started;
    }

    private URI serverAddress() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
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

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
