package com.coffeeorder.domain.idempotency.repository;

import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.entity.IdempotencyRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRequestRepository extends JpaRepository<IdempotencyRequest, Long> {

    Optional<IdempotencyRequest> findByUserIdAndOperationAndIdempotencyKey(
            long userId, IdempotencyOperation operation, String idempotencyKey);
}
