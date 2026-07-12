package com.coffeeorder.infra.outbox;

import com.coffeeorder.domain.outbox.service.OrderEventRecorded;
import com.coffeeorder.domain.outbox.service.OutboxDeliveryWorker;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 빠른 after-commit wake-up과 주기 스캔을 함께 제공하는 Outbox 실행 진입점.
 *
 * <p>application event는 성능 최적화이고, scheduler가 내구성 있는 DB 스캔을 담당한다. 두 경로 모두 같은 worker를 사용해 중복 cycle을
 * 방지한다.
 */
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

    /** 유실된 로컬 신호와 재시도 예약을 보완하기 위해 DB를 주기적으로 스캔한다. */
    @Scheduled(fixedDelayString = "${outbox.delivery.poll-interval:1s}")
    public void scheduledScan() {
        runSafely("scheduler");
    }

    /**
     * 새 Outbox 행이 실제로 커밋된 뒤 비동기 cycle을 빠르게 예약한다.
     *
     * <p>이벤트 payload는 전달하지 않고 worker가 DB에서 후보를 다시 선점하므로, signal의 중복이나 executor 거절이 상태 정합성에 영향을 주지
     * 않는다.
     */
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
