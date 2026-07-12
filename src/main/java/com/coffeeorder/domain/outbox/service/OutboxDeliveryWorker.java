package com.coffeeorder.domain.outbox.service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 한 JVM 안에서 겹치지 않게 Outbox 전달 cycle을 실행한다.
 *
 * <p>{@link AtomicBoolean}은 scheduler와 after-commit wake-up이 동시에 들어와도 로컬 cycle 하나만 실행하게 한다. 서로 다른
 * 인스턴스 사이의 조정은 DB의 행 잠금과 claim token이 담당한다.
 */
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

    /**
     * 현재 처리 가능한 이벤트를 모두 처리하는 한 번의 production delivery cycle을 실행한다.
     *
     * <p>이미 같은 JVM에서 cycle이 실행 중이면 중복 실행 대신 {@code 0}을 반환한다.
     */
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
