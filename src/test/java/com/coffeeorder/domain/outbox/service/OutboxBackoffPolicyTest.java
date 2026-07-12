package com.coffeeorder.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("아웃박스 재시도 지연 정책")
class OutboxBackoffPolicyTest {

    private final OutboxBackoffPolicy policy = new OutboxBackoffPolicy();

    @DisplayName("결정적인 지수 지연과 지터 범위를 적용한다")
    @Test
    void appliesDeterministicExponentialDelayAndJitterBounds() {
        assertThat(policy.retryDelay(1, 0.8)).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.retryDelay(1, 1.2)).isEqualTo(Duration.ofMillis(1200));
        assertThat(policy.retryDelay(5, 1.0)).isEqualTo(Duration.ofSeconds(16));
    }

    @DisplayName("지연 시간을 300초로 제한한다")
    @Test
    void capsDelayAtThreeHundredSeconds() {
        assertThat(policy.retryDelay(9, 0.8)).isEqualTo(Duration.ofMillis(204_800));
        assertThat(policy.retryDelay(9, 1.2)).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.retryDelay(10, 0.8)).isEqualTo(Duration.ofSeconds(300));
        assertThat(policy.retryDelay(10, 1.2)).isEqualTo(Duration.ofSeconds(300));
    }

    @DisplayName("문서화된 정책 범위를 벗어난 값을 거절한다")
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
