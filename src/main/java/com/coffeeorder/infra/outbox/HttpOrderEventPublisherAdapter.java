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
