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

@Component
public class OperationalMetrics {

    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);

    private final MeterRegistry meterRegistry;

    public OperationalMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

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
