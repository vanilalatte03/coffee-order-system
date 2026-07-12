package com.coffeeorder.global.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 비즈니스 흐름을 실패시키지 않는 best-effort Micrometer 기록 창구.
 *
 * <p>메트릭 registry나 계측 설정의 예외는 debug 로그로만 남긴다. 따라서 관측성 장애가 주문, 결제, Outbox 상태 전이를 rollback시키지 않는다.
 */
@Component
public class OperationalMetrics {

    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);

    private final MeterRegistry meterRegistry;

    public OperationalMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Spring bean이 아직 없는 경로에서도 안전하게 사용할 임시 registry 기반 인스턴스를 만든다. */
    public static OperationalMetrics fallback() {
        return new OperationalMetrics(new SimpleMeterRegistry());
    }

    public void increment(String name, String... tags) {
        safely(() -> meterRegistry.counter(name, tags).increment());
    }

    public void record(String name, Duration duration, String... tags) {
        safely(() -> Timer.builder(name).tags(tags).register(meterRegistry).record(duration));
    }

    public void gauge(String name, DoubleSupplier value, String... tags) {
        safely(
                () ->
                        Gauge.builder(name, () -> value.getAsDouble())
                                .tags(tags)
                                .strongReference(true)
                                .register(meterRegistry));
    }

    private static void safely(Runnable instrumentation) {
        try {
            instrumentation.run();
        } catch (RuntimeException exception) {
            log.debug("metric_recording_failed errorType={}", exception.getClass().getSimpleName());
        }
    }
}
