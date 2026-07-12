package com.coffeeorder.domain.outbox.repository;

import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxObservabilityRepository {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final TransactionTemplate readTransaction;

    public OutboxObservabilityRepository(
            JdbcTemplate jdbcTemplate, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
    }

    public long count(OutboxStatus status) {
        Long count =
                readTransaction.execute(
                        ignored ->
                                jdbcTemplate.queryForObject(
                                        "SELECT COUNT(*) FROM outbox_events WHERE status = ?",
                                        Long.class,
                                        status.name()));
        return count == null ? 0 : count;
    }

    public double oldestPendingWaitSeconds() {
        Instant createdAt =
                readTransaction.execute(
                        ignored ->
                                jdbcTemplate.queryForObject(
                                        "SELECT MIN(created_at) FROM outbox_events WHERE status = 'PENDING'",
                                        (resultSet, rowNumber) -> {
                                            Timestamp value = resultSet.getTimestamp(1);
                                            return value == null ? null : value.toInstant();
                                        }));
        if (createdAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(createdAt, clock.instant()).toMillis() / 1000.0);
    }
}
