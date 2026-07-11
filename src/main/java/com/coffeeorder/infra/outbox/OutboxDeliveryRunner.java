package com.coffeeorder.infra.outbox;

import com.coffeeorder.domain.outbox.service.OrderEventRecorded;
import com.coffeeorder.domain.outbox.service.OutboxDeliveryWorker;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class OutboxDeliveryRunner {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryRunner.class);

    private final OutboxDeliveryWorker worker;
    private final Executor executor;
    private final boolean afterCommitWakeupEnabled;

    OutboxDeliveryRunner(
            OutboxDeliveryWorker worker, Executor executor, boolean afterCommitWakeupEnabled) {
        this.worker = worker;
        this.executor = executor;
        this.afterCommitWakeupEnabled = afterCommitWakeupEnabled;
    }

    @Scheduled(fixedDelayString = "${outbox.delivery.poll-interval:1s}")
    public void scheduledScan() {
        runSafely("scheduler");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void wakeAfterCommit(OrderEventRecorded event) {
        if (afterCommitWakeupEnabled) {
            executor.execute(() -> runSafely("after_commit"));
        }
    }

    private void runSafely(String trigger) {
        try {
            worker.runOneCycle();
        } catch (RuntimeException exception) {
            log.error(
                    "outbox_cycle_failed trigger={} errorType={}",
                    trigger,
                    exception.getClass().getSimpleName());
        }
    }
}
