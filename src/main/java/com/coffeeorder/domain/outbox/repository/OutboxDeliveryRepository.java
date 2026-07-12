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

/**
 * Outbox 선점과 상태 전이를 SQL 조건부 갱신으로 수행하는 repository.
 *
 * <p>후보 ID를 먼저 정렬해 찾은 뒤 행마다 {@code FOR UPDATE SKIP LOCKED}를 시도한다. 경쟁 작업자가 먼저 가져간 후보는 건너뛰고 다음 후보를
 * 확인하므로 여러 인스턴스가 같은 이벤트를 동시에 발행하지 않는다. 모든 선점·상태 전이 호출은 짧은 트랜잭션 안에서 이뤄져야 한다.
 */
@Repository
public class OutboxDeliveryRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;

    public OutboxDeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 예약 시각이 지난 {@code PENDING} 또는 lease가 만료된 {@code PROCESSING} 이벤트 하나를 잠근다.
     *
     * <p>반환된 후보는 호출 트랜잭션이 끝나기 전에 상태 전이해야 한다. 빈 값은 처리 대상이 없거나 모든 후보를 다른 작업자가 선점한 경우다.
     */
    public Optional<LockedCandidate> lockNextCandidate(Instant now, int maxAttempts) {
        for (CandidateId candidateId : candidateIds(now, maxAttempts)) {
            List<LockedCandidate> locked = lockCandidate(candidateId, now, maxAttempts);
            if (!locked.isEmpty()) {
                return Optional.of(locked.getFirst());
            }
        }
        return Optional.empty();
    }

    /**
     * 잠긴 후보를 새 lease와 claim token을 가진 {@code PROCESSING} 상태로 전이한다.
     *
     * <p>기대 상태와 시도 횟수를 WHERE 절에 함께 둬, 후보 조회 뒤 상태가 바뀐 행을 잘못 선점하지 않는다.
     */
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

    /**
     * lease가 만료된 마지막 시도를 외부 재호출 없이 격리한다.
     *
     * <p>{@code attemptCount}가 이미 한도인 행만 갱신해 자동 시도 횟수가 11을 넘지 않게 한다.
     */
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
                    SELECT event_id, payload, status, attempt_count, created_at
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
                SELECT event_id, payload, status, attempt_count, created_at
                FROM outbox_events
                WHERE event_id = ? AND status = 'PROCESSING' AND locked_until < ?
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNum) -> lockedCandidate(resultSet),
                candidateId.eventId(),
                Timestamp.from(now));
    }

    /** 현재 claim token 소유자의 성공 결과만 {@code PUBLISHED}로 반영한다. */
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

    /**
     * 재시도 가능한 실패를 다음 예약 시각의 {@code PENDING} 상태로 되돌린다.
     *
     * <p>claim token이 일치할 때만 lease를 비우므로, 만료 뒤 회수된 이벤트의 늦은 실패 결과는 무시된다.
     */
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

    /** 영구 실패 또는 현재 시도 한도 소진을 현재 claim token 소유자에게만 반영한다. */
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

    /**
     * 운영자가 격리 이벤트를 새 자동 처리 주기로 재개할 때 호출한다.
     *
     * <p>시도 횟수와 lease만 초기화하고 마지막 실패 원인은 보존한다.
     */
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
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private static String limitedError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /** 현재 트랜잭션에서 잠긴 Outbox 행의 발행에 필요한 최소 snapshot. */
    public record LockedCandidate(
            String eventId,
            String payload,
            OutboxStatus status,
            int attemptCount,
            Instant createdAt) {}

    private record CandidateId(String eventId, OutboxStatus status) {}
}
