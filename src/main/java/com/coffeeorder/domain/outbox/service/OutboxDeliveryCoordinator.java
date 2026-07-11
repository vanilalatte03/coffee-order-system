package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository.ClaimOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class OutboxDeliveryCoordinator {

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
                        transaction.execute(
                                ignored ->
                                        repository.claimNext(
                                                workerId,
                                                claimedAt,
                                                normalize(claimedAt.plus(leaseDuration)))));
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

    private void applyResult(
            ClaimedOrderEvent event, OrderEventPublishResult result, Instant completedAt) {
        switch (result.type()) {
            case SUCCESS -> repository.markPublished(event, completedAt);
            case PERMANENT_FAILURE -> repository.markFailed(event, completedAt, result.error());
            case RETRYABLE_FAILURE -> {
                if (event.attemptCount() >= 11) {
                    repository.markFailed(event, completedAt, result.error());
                } else {
                    Duration delay =
                            backoffPolicy.retryDelay(
                                    event.attemptCount(), jitterFactor.getAsDouble());
                    repository.markPending(
                            event, normalize(completedAt.plus(delay)), completedAt, result.error());
                }
            }
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
}
