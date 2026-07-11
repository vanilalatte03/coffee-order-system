package com.coffeeorder.domain.outbox.repository;

import com.coffeeorder.domain.outbox.service.ClaimedOrderEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxDeliveryRepository {

    private static final int MAX_ATTEMPTS = 11;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;

    public OutboxDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ClaimOutcome claimNext(String workerId, Instant now, Instant lockedUntil) {
        Candidate candidate = null;
        for (CandidateId candidateId : candidateIds(now)) {
            List<Candidate> locked = lockCandidate(candidateId, now);
            if (!locked.isEmpty()) {
                candidate = locked.getFirst();
                break;
            }
        }
        if (candidate == null) {
            return ClaimOutcome.none();
        }
        if (candidate.status().equals("PROCESSING") && candidate.attemptCount() == MAX_ATTEMPTS) {
            jdbcTemplate.update(
                    """
                    UPDATE outbox_events
                    SET status = 'FAILED', claim_token = NULL, locked_by = NULL,
                        locked_until = NULL, last_error = ?, updated_at = ?
                    WHERE event_id = ? AND status = 'PROCESSING' AND attempt_count = 11
                    """,
                    "dispatch limit reached after lease expiration",
                    Timestamp.from(now),
                    candidate.eventId());
            return ClaimOutcome.quarantined(candidate.eventId());
        }

        String claimToken = UUID.randomUUID().toString();
        int nextAttempt = candidate.attemptCount() + 1;
        jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PROCESSING', attempt_count = ?, claim_token = ?, locked_by = ?,
                    locked_until = ?, updated_at = ?
                WHERE event_id = ?
                """,
                nextAttempt,
                claimToken,
                workerId,
                Timestamp.from(lockedUntil),
                Timestamp.from(now),
                candidate.eventId());
        return ClaimOutcome.claimed(
                new ClaimedOrderEvent(
                        candidate.eventId(), candidate.payload(), nextAttempt, claimToken));
    }

    private List<CandidateId> candidateIds(Instant now) {
        return jdbcTemplate.query(
                """
                SELECT candidate.event_id, candidate.status
                FROM (
                    SELECT event_id, status, next_attempt_at AS eligible_at, created_at
                    FROM outbox_events
                    WHERE status = 'PENDING' AND attempt_count < 11 AND next_attempt_at <= ?
                    UNION ALL
                    SELECT event_id, status, locked_until AS eligible_at, created_at
                    FROM outbox_events
                    WHERE status = 'PROCESSING' AND locked_until < ?
                ) candidate
                ORDER BY candidate.eligible_at, candidate.created_at, candidate.event_id
                """,
                (resultSet, rowNum) ->
                        new CandidateId(
                                resultSet.getString("event_id"), resultSet.getString("status")),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private List<Candidate> lockCandidate(CandidateId candidateId, Instant now) {
        if (candidateId.status().equals("PENDING")) {
            return jdbcTemplate.query(
                    """
                    SELECT event_id, payload, status, attempt_count
                    FROM outbox_events
                    WHERE event_id = ? AND status = 'PENDING'
                        AND attempt_count < 11 AND next_attempt_at <= ?
                    FOR UPDATE SKIP LOCKED
                    """,
                    (resultSet, rowNum) -> candidate(resultSet),
                    candidateId.eventId(),
                    Timestamp.from(now));
        }
        return jdbcTemplate.query(
                """
                SELECT event_id, payload, status, attempt_count
                FROM outbox_events
                WHERE event_id = ? AND status = 'PROCESSING' AND locked_until < ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNum) -> candidate(resultSet),
                candidateId.eventId(),
                Timestamp.from(now));
    }

    public int markPublished(ClaimedOrderEvent event, Instant publishedAt) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = ?, claim_token = NULL,
                    locked_by = NULL, locked_until = NULL, last_error = NULL, updated_at = ?
                WHERE event_id = ? AND status = 'PROCESSING' AND claim_token = ?
                """,
                Timestamp.from(publishedAt),
                Timestamp.from(publishedAt),
                event.eventId(),
                event.claimToken());
    }

    public int markPending(
            ClaimedOrderEvent event, Instant nextAttemptAt, Instant updatedAt, String error) {
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
                event.eventId(),
                event.claimToken());
    }

    public int markFailed(ClaimedOrderEvent event, Instant updatedAt, String error) {
        return jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'FAILED', claim_token = NULL, locked_by = NULL,
                    locked_until = NULL, last_error = ?, updated_at = ?
                WHERE event_id = ? AND status = 'PROCESSING' AND claim_token = ?
                """,
                limitedError(error),
                Timestamp.from(updatedAt),
                event.eventId(),
                event.claimToken());
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

    private static Candidate candidate(ResultSet resultSet) throws SQLException {
        return new Candidate(
                resultSet.getString("event_id"),
                resultSet.getString("payload"),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"));
    }

    private static String limitedError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private record Candidate(String eventId, String payload, String status, int attemptCount) {}

    private record CandidateId(String eventId, String status) {}

    public record ClaimOutcome(Kind kind, Optional<ClaimedOrderEvent> event, String eventId) {

        public enum Kind {
            NONE,
            CLAIMED,
            QUARANTINED
        }

        static ClaimOutcome none() {
            return new ClaimOutcome(Kind.NONE, Optional.empty(), null);
        }

        static ClaimOutcome claimed(ClaimedOrderEvent event) {
            return new ClaimOutcome(Kind.CLAIMED, Optional.of(event), event.eventId());
        }

        static ClaimOutcome quarantined(String eventId) {
            return new ClaimOutcome(Kind.QUARANTINED, Optional.empty(), eventId);
        }
    }
}
