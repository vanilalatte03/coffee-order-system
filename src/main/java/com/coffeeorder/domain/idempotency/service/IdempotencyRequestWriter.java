package com.coffeeorder.domain.idempotency.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyRequest;
import com.coffeeorder.domain.idempotency.repository.IdempotencyRequestRepository;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyRequestWriter {

    private final IdempotencyRequestRepository idempotencyRequestRepository;

    public IdempotencyRequestWriter(IdempotencyRequestRepository idempotencyRequestRepository) {
        this.idempotencyRequestRepository = idempotencyRequestRepository;
    }

    public void flushProcessing(IdempotencyRequest request) {
        idempotencyRequestRepository.saveAndFlush(request);
    }

    public void flushCompleted(IdempotencyRequest request) {
        idempotencyRequestRepository.saveAndFlush(request);
    }
}
