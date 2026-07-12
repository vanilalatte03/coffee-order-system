package com.coffeeorder.infra.outbox;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
