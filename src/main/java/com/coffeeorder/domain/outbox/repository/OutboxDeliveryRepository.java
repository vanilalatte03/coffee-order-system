package com.coffeeorder.domain.outbox.repository;

import com.coffeeorder.domain.outbox.entity.OutboxStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxDeliveryRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;

    public OutboxDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LockedCandidate> lockNextCandidate(Instant now, int maxAttempts) {
        for (CandidateId candidateId : candidateIds(now, maxAttempts)) {
            List<LockedCandidate> locked = lockCandidate(candidateId, now, maxAttempts);
            if (!locked.isEmpty()) {
                return Optional.of(locked.getFirst());
            }
        }
        return Optional.empty();
    }

    public int markProcessing(
            String eventId,
            OutboxStatus expectedStatus,
            int expectedAttemptCount,
            int attemptCount,
            String claimToken,
            String workerId,
            Instant lockedUntil,
            Instant updatedAt) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PROCESSING', attempt_count = ?, claim_token = ?, locked_by = ?,
                    locked_until = ?, updated_at = ?
                WHERE event_id = ? AND status = ? AND attempt_count = ?
                """,
                attemptCount,
                claimToken,
                workerId,
                Timestamp.from(lockedUntil),
                Timestamp.from(updatedAt),
                eventId,
                expectedStatus.name(),
                expectedAttemptCount);
    }

    public int markFailedByAttemptCount(
            String eventId, int expectedAttemptCount, Instant updatedAt, String error) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'FAILED', claim_token = NULL, locked_by = NULL,
                    locked_until = NULL, last_error = ?, updated_at = ?
                WHERE event_id = ? AND status = 'PROCESSING' AND attempt_count = ?
                """,
                limitedError(error),
                Timestamp.from(updatedAt),
                eventId,
                expectedAttemptCount);
    }

    private List<CandidateId> candidateIds(Instant now, int maxAttempts) {
        return jdbcTemplate.query(
                """
                SELECT candidate.event_id, candidate.status
                FROM (
                    SELECT event_id, status, next_attempt_at AS eligible_at, created_at
                    FROM outbox_events
                    WHERE status = 'PENDING' AND attempt_count < ? AND next_attempt_at <= ?
                    UNION ALL
                    SELECT event_id, status, locked_until AS eligible_at, created_at
                    FROM outbox_events
                    WHERE status = 'PROCESSING' AND locked_until < ?
                ) candidate
                ORDER BY candidate.eligible_at, candidate.created_at, candidate.event_id
                """,
                (resultSet, rowNum) ->
                        new CandidateId(
                                resultSet.getString("event_id"),
                                OutboxStatus.valueOf(resultSet.getString("status"))),
                maxAttempts,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private List<LockedCandidate> lockCandidate(
            CandidateId candidateId, Instant now, int maxAttempts) {
        if (candidateId.status() == OutboxStatus.PENDING) {
            return jdbcTemplate.query(
                    """
                    SELECT event_id, payload, status, attempt_count
                    FROM outbox_events
                    WHERE event_id = ? AND status = 'PENDING'
                        AND attempt_count < ? AND next_attempt_at <= ?
                    FOR UPDATE SKIP LOCKED
                    """,
                    (resultSet, rowNum) -> lockedCandidate(resultSet),
                    candidateId.eventId(),
                    maxAttempts,
                    Timestamp.from(now));
        }
        return jdbcTemplate.query(
                """
                SELECT event_id, payload, status, attempt_count
                FROM outbox_events
                WHERE event_id = ? AND status = 'PROCESSING' AND locked_until < ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNum) -> lockedCandidate(resultSet),
                candidateId.eventId(),
                Timestamp.from(now));
    }

    public int markPublished(String eventId, String claimToken, Instant publishedAt) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = ?, claim_token = NULL,
                    locked_by = NULL, locked_until = NULL, last_error = NULL, updated_at = ?
                WHERE event_id = ? AND status = 'PROCESSING' AND claim_token = ?
                """,
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt),
                eventId,
                claimToken);
    }

    public int markPending(
            String eventId,
            String claimToken,
            Instant nextAttemptAt,
            Instant updatedAt,
            String error) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PENDING', next_attempt_at = ?, claim_token = NULL,
                    locked_by = NULL, locked_until = NULL, last_error = ?, updated_at = ?
                WHERE event_id = ? AND status = 'PROCESSING' AND claim_token = ?
                """,
                Timestamp.from(nextAttemptAt),
                limitedError(error),
                Timestamp.from(updatedAt),
                eventId,
                claimToken);
    }

    public int markFailedByClaimToken(
            String eventId, String claimToken, Instant updatedAt, String error) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'FAILED', claim_token = NULL, locked_by = NULL,
                    locked_until = NULL, last_error = ?, updated_at = ?
                WHERE event_id = ? AND status = 'PROCESSING' AND claim_token = ?
                """,
                limitedError(error),
                Timestamp.from(updatedAt),
                eventId,
                claimToken);
    }

    public int resetFailed(String eventId, Instant now) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = ?,
                    claim_token = NULL, locked_by = NULL, locked_until = NULL,
                    published_at = NULL, updated_at = ?
                WHERE event_id = ? AND status = 'FAILED'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                eventId);
    }

    private static LockedCandidate lockedCandidate(ResultSet resultSet) throws SQLException {
        return new LockedCandidate(
                resultSet.getString("event_id"),
                resultSet.getString("payload"),
                OutboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"));
    }

    private static String limitedError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public record LockedCandidate(
            String eventId, String payload, OutboxStatus status, int attemptCount) {}

    private record CandidateId(String eventId, OutboxStatus status) {}
}
