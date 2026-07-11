package com.coffeeorder.domain.outbox.service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class OutboxDeliveryWorker {

    private final OutboxDeliveryCoordinator coordinator;
    private final String workerId;
    private final AtomicBoolean running = new AtomicBoolean();

    public OutboxDeliveryWorker(OutboxDeliveryCoordinator coordinator, String workerId) {
        this.coordinator = Objects.requireNonNull(coordinator);
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("worker id must be between 1 and 100 characters");
        }
        this.workerId = workerId;
    }

    /** Processes every event currently eligible for one production delivery cycle. */
    public int runOneCycle() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        int processed = 0;
        try {
            while (coordinator.dispatchNext(workerId)) {
                processed++;
            }
            return processed;
        } finally {
            running.set(false);
        }
    }
}
