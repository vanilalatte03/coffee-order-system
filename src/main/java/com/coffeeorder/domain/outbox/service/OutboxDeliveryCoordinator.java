package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository.LockedCandidate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class OutboxDeliveryCoordinator {

    private static final int MAX_ATTEMPTS = 11;
    private static final String ATTEMPT_LIMIT_ERROR =
            "dispatch limit reached after lease expiration";

    private final OutboxDeliveryRepository repository;
    private final OrderEventPublisher publisher;
    private final Clock clock;
    private final Duration leaseDuration;
    private final OutboxBackoffPolicy backoffPolicy;
    private final DoubleSupplier jitterFactor;
    private final TransactionTemplate transaction;

    public OutboxDeliveryCoordinator(
            OutboxDeliveryRepository repository,
            OrderEventPublisher publisher,
            Clock clock,
            Duration leaseDuration,
            OutboxBackoffPolicy backoffPolicy,
            DoubleSupplier jitterFactor,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository);
        this.publisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        this.leaseDuration = leaseDuration;
        this.backoffPolicy = Objects.requireNonNull(backoffPolicy);
        this.jitterFactor = Objects.requireNonNull(jitterFactor);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    public boolean dispatchNext(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("worker id must be between 1 and 100 characters");
        }
        Instant claimedAt = now();
        ClaimOutcome outcome =
                Objects.requireNonNull(
                        transaction.execute(ignored -> claimNext(workerId, claimedAt)));
        if (outcome.kind() == ClaimOutcome.Kind.NONE) {
            return false;
        }
        if (outcome.kind() == ClaimOutcome.Kind.QUARANTINED) {
            return true;
        }

        ClaimedOrderEvent event = outcome.event().orElseThrow();
        OrderEventPublishResult result;
        try {
            result = Objects.requireNonNull(publisher.publish(event));
        } catch (RuntimeException exception) {
            result = OrderEventPublishResult.retryableFailure(exceptionDescription(exception));
        }
        Instant completedAt = now();
        OrderEventPublishResult finalResult = result;
        transaction.executeWithoutResult(ignored -> applyResult(event, finalResult, completedAt));
        return true;
    }

    private ClaimOutcome claimNext(String workerId, Instant claimedAt) {
        Optional<LockedCandidate> locked = repository.lockNextCandidate(claimedAt, MAX_ATTEMPTS);
        if (locked.isEmpty()) {
            return ClaimOutcome.none();
        }

        LockedCandidate candidate = locked.orElseThrow();
        if (candidate.status() == OutboxStatus.PROCESSING
                && candidate.attemptCount() == MAX_ATTEMPTS) {
            requireSingleUpdate(
                    repository.markFailedByAttemptCount(
                            candidate.eventId(),
                            candidate.attemptCount(),
                            claimedAt,
                            ATTEMPT_LIMIT_ERROR));
            return ClaimOutcome.quarantined(candidate.eventId());
        }

        int attemptCount = candidate.attemptCount() + 1;
        String claimToken = UUID.randomUUID().toString();
        requireSingleUpdate(
                repository.markProcessing(
                        candidate.eventId(),
                        candidate.status(),
                        candidate.attemptCount(),
                        attemptCount,
                        claimToken,
                        workerId,
                        normalize(claimedAt.plus(leaseDuration)),
                        claimedAt));
        return ClaimOutcome.claimed(
                new ClaimedOrderEvent(
                        candidate.eventId(), candidate.payload(), attemptCount, claimToken));
    }

    private void applyResult(
            ClaimedOrderEvent event, OrderEventPublishResult result, Instant completedAt) {
        switch (result.type()) {
            case SUCCESS ->
                    repository.markPublished(event.eventId(), event.claimToken(), completedAt);
            case PERMANENT_FAILURE ->
                    repository.markFailedByClaimToken(
                            event.eventId(), event.claimToken(), completedAt, result.error());
            case RETRYABLE_FAILURE -> {
                if (event.attemptCount() >= MAX_ATTEMPTS) {
                    repository.markFailedByClaimToken(
                            event.eventId(), event.claimToken(), completedAt, result.error());
                } else {
                    Duration delay =
                            backoffPolicy.retryDelay(
                                    event.attemptCount(), jitterFactor.getAsDouble());
                    repository.markPending(
                            event.eventId(),
                            event.claimToken(),
                            normalize(completedAt.plus(delay)),
                            completedAt,
                            result.error());
                }
            }
        }
    }

    private static void requireSingleUpdate(int updatedRows) {
        if (updatedRows != 1) {
            throw new IllegalStateException("locked outbox candidate update must affect one row");
        }
    }

    private Instant now() {
        return normalize(clock.instant());
    }

    private static Instant normalize(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String exceptionDescription(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record ClaimOutcome(Kind kind, Optional<ClaimedOrderEvent> event, String eventId) {

        private enum Kind {
            NONE,
            CLAIMED,
            QUARANTINED
        }

        private static ClaimOutcome none() {
            return new ClaimOutcome(Kind.NONE, Optional.empty(), null);
        }

        private static ClaimOutcome claimed(ClaimedOrderEvent event) {
            return new ClaimOutcome(Kind.CLAIMED, Optional.of(event), event.eventId());
        }

        private static ClaimOutcome quarantined(String eventId) {
            return new ClaimOutcome(Kind.QUARANTINED, Optional.empty(), eventId);
        }
    }
}
