package com.coffeeorder.domain.idempotency.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyRequest;
import com.coffeeorder.domain.idempotency.repository.IdempotencyRequestRepository;
import org.springframework.stereotype.Component;

/**
 * 멱등성 상태 변경을 즉시 SQL로 반영하는 전용 writer.
 *
 * <p>신규 {@code PROCESSING} 행을 먼저 flush해 같은 키의 유니크 충돌을 현재 트랜잭션에서 감지하고, 완료 snapshot도 flush해 저장 실패가
 * 도메인 변경 전체를 롤백하게 만든다.
 */
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
