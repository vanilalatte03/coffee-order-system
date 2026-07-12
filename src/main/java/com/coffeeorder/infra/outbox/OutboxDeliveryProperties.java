package com.coffeeorder.infra.outbox;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox HTTP 전달의 시간 제한, lease, 폴링과 worker 식별자 설정.
 *
 * <p>모든 duration은 양수여야 하며, base URL과 worker ID는 DB lease 및 외부 전달 경계에서 바로 사용되므로 애플리케이션 시작 시 검증한다.
 */
@ConfigurationProperties("outbox.delivery")
public record OutboxDeliveryProperties(
        boolean enabled,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration pollInterval,
        Duration lease,
        String workerId,
        boolean afterCommitWakeupEnabled) {

    public OutboxDeliveryProperties {
        requirePositive(connectTimeout, "connect timeout");
        requirePositive(readTimeout, "read timeout");
        requirePositive(pollInterval, "poll interval");
        requirePositive(lease, "lease");
        if (baseUrl == null || !baseUrl.isAbsolute()) {
            throw new IllegalArgumentException("outbox base URL must be absolute");
        }
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("worker id must be between 1 and 100 characters");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
