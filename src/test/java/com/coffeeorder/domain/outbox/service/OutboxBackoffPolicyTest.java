package com.coffeeorder.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxBackoffPolicyTest {

    private final OutboxBackoffPolicy policy = new OutboxBackoffPolicy();

    @Test
    void appliesDeterministicExponentialDelayAndJitterBounds() {
        assertThat(policy.retryDelay(1, 0.8)).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.retryDelay(1, 1.2)).isEqualTo(Duration.ofMillis(1200));
        assertThat(policy.retryDelay(5, 1.0)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void capsDelayAtThreeHundredSeconds() {
        assertThat(policy.retryDelay(10, 0.8)).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.retryDelay(10, 1.2)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void rejectsValuesOutsideTheDocumentedPolicy() {
        assertThatThrownBy(() -> policy.retryDelay(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.retryDelay(1, 0.79))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.retryDelay(1, 1.21))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.retryDelay(1, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.retryDelay(1, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.retryDelay(1, Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
