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
