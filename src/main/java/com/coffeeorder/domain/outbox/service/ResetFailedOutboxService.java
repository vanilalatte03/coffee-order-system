package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResetFailedOutboxService {

    private final OutboxDeliveryRepository repository;
    private final Clock clock;

    public ResetFailedOutboxService(OutboxDeliveryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public boolean reset(String eventId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return repository.resetFailed(eventId, now) == 1;
    }
}
