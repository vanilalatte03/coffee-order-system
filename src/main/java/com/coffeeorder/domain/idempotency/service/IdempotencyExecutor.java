package com.coffeeorder.domain.idempotency.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.entity.IdempotencyRequest;
import com.coffeeorder.domain.idempotency.repository.IdempotencyRequestRepository;
import com.coffeeorder.global.observability.OperationalMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB 유니크 제약을 멱등성의 최종 조정자로 사용하는 유스케이스 실행기.
 *
 * <p>사전 검증은 쓰기 트랜잭션 밖에서 수행하고, 신규 키의 {@code PROCESSING} 생성·도메인 변경·완료 응답 저장은 하나의 새 트랜잭션에서 처리한다. 같은 키를
 * 동시에 선점한 요청은 유니크 제약 충돌 뒤 별도 읽기 트랜잭션에서 승자의 결과를 재생한다.
 *
 * <p>호출자가 이미 연 트랜잭션에 합류하면 충돌 뒤 재조회와 롤백 경계가 흐려지므로 외부 트랜잭션 안에서의 호출은 허용하지 않는다.
 */
@Service
public class IdempotencyExecutor {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final IdempotencyRequestRepository idempotencyRequestRepository;
    private final IdempotencyRequestWriter idempotencyRequestWriter;
    private final Clock clock;
    private final OperationalMetrics metrics;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public IdempotencyExecutor(
            IdempotencyRequestRepository idempotencyRequestRepository,
            IdempotencyRequestWriter idempotencyRequestWriter,
            Clock clock,
            PlatformTransactionManager transactionManager,
            OperationalMetrics metrics) {
        this.idempotencyRequestRepository = idempotencyRequestRepository;
        this.idempotencyRequestWriter = idempotencyRequestWriter;
        this.clock = clock;
        this.metrics = metrics;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * 요청 범위 {@code (userId, operation, idempotencyKey)}의 최초 실행 또는 완료 결과 재생을 수행한다.
     *
     * <p>기존 완료 기록이 있으면 {@code precondition}과 {@code work}를 실행하지 않는다. 해시가 다르면 키 재사용 오류를 내고, 같으면 저장된
     * 안정 결과를 반환한다. 최초 실행에서는 {@code work}가 반환한 성공 또는 결정적 오류 snapshot을 도메인 변경과 함께 저장한다.
     *
     * @param precondition 저장하지 않는 사전 검증 callback
     * @param work 멱등성 행과 같은 트랜잭션 안에서 실행할 도메인 callback
     */
    public IdempotencyExecutionResult execute(
            long userId,
            IdempotencyOperation operation,
            String idempotencyKey,
            CanonicalPayload canonicalPayload,
            Runnable precondition,
            IdempotencyWork work) {
        ensureNoSurroundingTransaction();
        validateRequest(userId, operation, idempotencyKey, canonicalPayload, precondition, work);
        String requestHash = canonicalPayload.sha256();

        Optional<IdempotencyRequest> existing =
                findInNewReadTransaction(userId, operation, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, operation);
        }

        precondition.run();

        try {
            IdempotencyResponseSnapshot snapshot =
                    writeTransaction.execute(
                            ignored ->
                                    executeInWriteTransaction(
                                            userId, operation, idempotencyKey, requestHash, work));
            metrics.increment(
                    "coffee.idempotency.requests",
                    "operation",
                    operation.name().toLowerCase(),
                    "outcome",
                    "first");
            return new IdempotencyExecutionResult(snapshot, false);
        } catch (DataIntegrityViolationException collisionOrConstraintFailure) {
            Optional<IdempotencyRequest> winner =
                    findInNewReadTransaction(userId, operation, idempotencyKey);
            if (winner.isEmpty()) {
                throw collisionOrConstraintFailure;
            }
            return replay(winner.get(), requestHash, operation);
        }
    }

    /**
     * 멱등성 행의 생성부터 완료 snapshot flush까지를 같은 물리 트랜잭션으로 묶는다.
     *
     * <p>마지막 flush가 실패하면 앞선 도메인 쓰기와 {@code PROCESSING} 행도 함께 롤백되어, 완료되지 않은 결과가 재생되는 일을 막는다.
     */
    private IdempotencyResponseSnapshot executeInWriteTransaction(
            long userId,
            IdempotencyOperation operation,
            String idempotencyKey,
            String requestHash,
            IdempotencyWork work) {
        IdempotencyRequest request =
                IdempotencyRequest.processing(
                        userId, operation, idempotencyKey, requestHash, now());
        idempotencyRequestWriter.flushProcessing(request);

        IdempotencyResponseSnapshot snapshot = work.execute();
        request.complete(snapshot.responseStatus(), snapshot.storedBody(), now());
        idempotencyRequestWriter.flushCompleted(request);
        return snapshot;
    }

    private Optional<IdempotencyRequest> findInNewReadTransaction(
            long userId, IdempotencyOperation operation, String idempotencyKey) {
        return readTransaction.execute(
                ignored ->
                        idempotencyRequestRepository.findByUserIdAndOperationAndIdempotencyKey(
                                userId, operation, idempotencyKey));
    }

    private IdempotencyExecutionResult replay(
            IdempotencyRequest request, String requestHash, IdempotencyOperation operation) {
        if (!request.hasRequestHash(requestHash)) {
            metrics.increment(
                    "coffee.idempotency.requests",
                    "operation",
                    operation.name().toLowerCase(),
                    "outcome",
                    "conflict");
            throw new IdempotencyKeyReusedException();
        }
        if (!request.isCompleted()) {
            throw new IdempotencyInProgressException();
        }
        IdempotencyResponseSnapshot snapshot =
                IdempotencyResponseSnapshot.restored(
                        request.getResponseStatus(), request.getResponseBody());
        metrics.increment(
                "coffee.idempotency.requests",
                "operation",
                operation.name().toLowerCase(),
                "outcome",
                "replay");
        return new IdempotencyExecutionResult(snapshot, true);
    }

    private static void validateRequest(
            long userId,
            IdempotencyOperation operation,
            String idempotencyKey,
            CanonicalPayload canonicalPayload,
            Runnable precondition,
            IdempotencyWork work) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (idempotencyKey == null || !KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
        if (canonicalPayload == null || precondition == null || work == null) {
            throw new IllegalArgumentException("idempotency callbacks and payload are required");
        }
    }

    private static void ensureNoSurroundingTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "idempotency executor must be invoked outside a surrounding transaction");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
