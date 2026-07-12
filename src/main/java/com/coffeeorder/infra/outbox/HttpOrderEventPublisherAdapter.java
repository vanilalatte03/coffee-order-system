package com.coffeeorder.infra.outbox;

import com.coffeeorder.domain.outbox.service.ClaimedOrderEvent;
import com.coffeeorder.domain.outbox.service.OrderEventPublishResult;
import com.coffeeorder.domain.outbox.service.OrderEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Phase 1 데이터 플랫폼 HTTP 계약을 {@link OrderEventPublisher} 포트로 변환한다.
 *
 * <p>모든 2xx는 성공, timeout·네트워크 오류·429·5xx는 재시도 가능, 그 밖의 4xx는 영구 실패로 분류한다. 이 어댑터는 결과만 반환하며 Outbox 상태
 * 변경은 coordinator가 수행한다.
 */
public class HttpOrderEventPublisherAdapter implements OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(HttpOrderEventPublisherAdapter.class);
    private static final String EVENT_PATH = "/api/v1/order-events";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public HttpOrderEventPublisherAdapter(
            OutboxDeliveryProperties properties, MeterRegistry meterRegistry) {
        this(createRestClient(properties), meterRegistry);
    }

    HttpOrderEventPublisherAdapter(RestClient restClient, MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.meterRegistry = meterRegistry;
    }

    /** 이벤트 ID 헤더와 저장된 JSON snapshot을 그대로 전송하고 HTTP 결과를 상태 전이용으로 분류한다. */
    @Override
    public OrderEventPublishResult publish(ClaimedOrderEvent event) {
        long startedAt = System.nanoTime();
        int[] status = {0};
        OrderEventPublishResult result;
        try {
            result =
                    restClient
                            .post()
                            .uri(EVENT_PATH)
                            .header("X-Event-Id", event.eventId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(event.payload())
                            .exchange(
                                    (request, response) -> {
                                        status[0] = response.getStatusCode().value();
                                        return classify(status[0]);
                                    });
        } catch (RuntimeException exception) {
            result =
                    OrderEventPublishResult.retryableFailure(
                            "network error (" + exception.getClass().getSimpleName() + ")");
        }
        long latencyNanos = System.nanoTime() - startedAt;
        String outcome = result.type().name().toLowerCase();
        meterRegistry.counter("outbox.publish.attempts", "result", outcome).increment();
        Timer.builder("outbox.publish.duration")
                .tag("result", outcome)
                .register(meterRegistry)
                .record(latencyNanos, TimeUnit.NANOSECONDS);
        if (result.type() == OrderEventPublishResult.Type.SUCCESS) {
            log.debug(
                    "outbox_publish eventId={} attempt={} result={} httpStatus={} latencyMs={}",
                    event.eventId(),
                    event.attemptCount(),
                    outcome,
                    status[0],
                    Duration.ofNanos(latencyNanos).toMillis());
        } else {
            log.warn(
                    "outbox_publish eventId={} attempt={} result={} httpStatus={} latencyMs={}",
                    event.eventId(),
                    event.attemptCount(),
                    outcome,
                    status[0] == 0 ? "none" : status[0],
                    Duration.ofNanos(latencyNanos).toMillis());
        }
        return result;
    }

    /** 수신자가 응답한 상태 코드를 자동 재시도 가능 여부로 구분한다. */
    private static OrderEventPublishResult classify(int status) {
        if (status >= 200 && status < 300) {
            return OrderEventPublishResult.success();
        }
        if (status == 429 || status >= 500) {
            return OrderEventPublishResult.retryableFailure("HTTP " + status);
        }
        return OrderEventPublishResult.permanentFailure("HTTP " + status);
    }

    private static RestClient createRestClient(OutboxDeliveryProperties properties) {
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
