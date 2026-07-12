package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 시도 한도나 영구 오류로 격리된 Outbox 이벤트를 운영자가 재처리할 수 있게 한다.
 *
 * <p>실패 원인은 삭제하지 않고 새 lease·시도 주기를 시작할 수 있는 {@code PENDING} 상태만 복구한다.
 */
@Service
public class ResetFailedOutboxService {

    private final OutboxDeliveryRepository repository;
    private final Clock clock;

    public ResetFailedOutboxService(OutboxDeliveryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 해당 이벤트가 {@code FAILED} 상태일 때만 새 처리 주기로 초기화한다. */
    @Transactional
    public boolean reset(String eventId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return repository.resetFailed(eventId, now) == 1;
    }
}
