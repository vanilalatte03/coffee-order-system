package com.coffeeorder.domain.outbox.service;

import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository;
import com.coffeeorder.domain.outbox.repository.OutboxDeliveryRepository.LockedCandidate;
import com.coffeeorder.global.observability.OperationalMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class OutboxDeliveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryCoordinator.class);

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
    private final OperationalMetrics metrics;

    public OutboxDeliveryCoordinator(
            OutboxDeliveryRepository repository,
            OrderEventPublisher publisher,
            Clock clock,
            Duration leaseDuration,
            OutboxBackoffPolicy backoffPolicy,
            DoubleSupplier jitterFactor,
            PlatformTransactionManager transactionManager) {
        this(
                repository,
                publisher,
                clock,
                leaseDuration,
                backoffPolicy,
                jitterFactor,
                transactionManager,
                OperationalMetrics.fallback());
    }

    public OutboxDeliveryCoordinator(
            OutboxDeliveryRepository repository,
            OrderEventPublisher publisher,
            Clock clock,
            Duration leaseDuration,
            OutboxBackoffPolicy backoffPolicy,
            DoubleSupplier jitterFactor,
            PlatformTransactionManager transactionManager,
            OperationalMetrics metrics) {
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
        this.metrics = Objects.requireNonNull(metrics);
    }

    public boolean dispatchNext(String workerId) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("worker id must be between 1 and 100 characters");
        }
        Instant eligibilityAt = now();
        ClaimOutcome outcome =
                Objects.requireNonNull(
                        transaction.execute(ignored -> claimNext(workerId, eligibilityAt)));
        if (outcome.kind() == ClaimOutcome.Kind.NONE) {
            return false;
        }
        if (outcome.kind() == ClaimOutcome.Kind.QUARANTINED) {
            metrics.increment("coffee.outbox.delivery.results", "result", "quarantine");
            log.error(
                    "outbox_quarantined eventId={} operation=ORDER_PAID resultCode=ATTEMPT_LIMIT",
                    outcome.eventId());
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
        boolean applied =
                Boolean.TRUE.equals(
                        transaction.execute(
                                ignored -> applyResult(event, finalResult, completedAt)));
        if (applied) {
            recordDeliveryMetrics(event, result, completedAt);
        } else {
            metrics.increment("coffee.outbox.delivery.results", "result", "stale_claim");
            log.warn(
                    "outbox_delivery_stale_claim eventId={} attempt={} operation=ORDER_PAID resultCode=STALE_CLAIM",
                    event.eventId(),
                    event.attemptCount());
        }
        return true;
    }

    private ClaimOutcome claimNext(String workerId, Instant eligibilityAt) {
        Optional<LockedCandidate> locked =
                repository.lockNextCandidate(eligibilityAt, MAX_ATTEMPTS);
        if (locked.isEmpty()) {
            return ClaimOutcome.none();
        }

        LockedCandidate candidate = locked.orElseThrow();
        Instant leaseStartedAt = now();
        if (candidate.status() == OutboxStatus.PROCESSING
                && candidate.attemptCount() == MAX_ATTEMPTS) {
            requireSingleUpdate(
                    repository.markFailedByAttemptCount(
                            candidate.eventId(),
                            candidate.attemptCount(),
                            leaseStartedAt,
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
                        normalize(leaseStartedAt.plus(leaseDuration)),
                        leaseStartedAt));
        return ClaimOutcome.claimed(
                new ClaimedOrderEvent(
                        candidate.eventId(),
                        candidate.payload(),
                        attemptCount,
                        claimToken,
                        candidate.createdAt()));
    }

    private boolean applyResult(
            ClaimedOrderEvent event, OrderEventPublishResult result, Instant completedAt) {
        int updatedRows =
                switch (result.type()) {
                    case SUCCESS ->
                            repository.markPublished(
                                    event.eventId(), event.claimToken(), completedAt);
                    case PERMANENT_FAILURE ->
                            repository.markFailedByClaimToken(
                                    event.eventId(),
                                    event.claimToken(),
                                    completedAt,
                                    result.error());
                    case RETRYABLE_FAILURE -> {
                        if (event.attemptCount() >= MAX_ATTEMPTS) {
                            yield repository.markFailedByClaimToken(
                                    event.eventId(),
                                    event.claimToken(),
                                    completedAt,
                                    result.error());
                        } else {
                            Duration delay =
                                    backoffPolicy.retryDelay(
                                            event.attemptCount(), jitterFactor.getAsDouble());
                            yield repository.markPending(
                                    event.eventId(),
                                    event.claimToken(),
                                    normalize(completedAt.plus(delay)),
                                    completedAt,
                                    result.error());
                        }
                    }
                };
        return updatedRows == 1;
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

    private void recordDeliveryMetrics(
            ClaimedOrderEvent event, OrderEventPublishResult result, Instant completedAt) {
        String resultCode = result.type().name().toLowerCase();
        metrics.increment("coffee.outbox.delivery.results", "result", resultCode);
        metrics.increment(
                "coffee.outbox.delivery.attempts",
                "kind",
                event.attemptCount() == 1 ? "first" : "retry");
        if (event.attemptCount() == 1 && !event.createdAt().equals(Instant.EPOCH)) {
            Duration firstLatency = Duration.between(event.createdAt(), completedAt);
            metrics.record(
                    "coffee.outbox.delivery.first.latency",
                    firstLatency.isNegative() ? Duration.ZERO : firstLatency,
                    "result",
                    resultCode);
        }
        if (result.type() == OrderEventPublishResult.Type.SUCCESS) {
            log.info(
                    "outbox_delivery_completed eventId={} attempt={} operation=ORDER_PAID resultCode={}",
                    event.eventId(),
                    event.attemptCount(),
                    result.type());
        } else {
            log.warn(
                    "outbox_delivery_failed eventId={} attempt={} operation=ORDER_PAID resultCode={}",
                    event.eventId(),
                    event.attemptCount(),
                    result.type());
        }
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
