package com.coffeeorder.global.observability;

import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import com.coffeeorder.domain.outbox.repository.OutboxObservabilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OutboxStateMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxStateMetrics.class);

    private final OutboxObservabilityRepository repository;

    public OutboxStateMetrics(
            OutboxObservabilityRepository repository, OperationalMetrics metrics) {
        this.repository = repository;
        for (OutboxStatus status :
                new OutboxStatus[] {
                    OutboxStatus.PENDING, OutboxStatus.PROCESSING, OutboxStatus.FAILED
                }) {
            metrics.gauge(
                    "coffee.outbox.events",
                    () -> countSafely(status),
                    "status",
                    status.name().toLowerCase());
        }
        metrics.gauge("coffee.outbox.oldest.pending.seconds", this::oldestPendingWaitSafely);
    }

    private double countSafely(OutboxStatus status) {
        try {
            return repository.count(status);
        } catch (RuntimeException exception) {
            log.debug(
                    "outbox_metric_read_failed metric=count status={} errorType={}",
                    status,
                    exception.getClass().getSimpleName());
            return Double.NaN;
        }
    }

    private double oldestPendingWaitSafely() {
        try {
            return repository.oldestPendingWaitSeconds();
        } catch (RuntimeException exception) {
            log.debug(
                    "outbox_metric_read_failed metric=oldest_wait errorType={}",
                    exception.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
