package com.coffeeorder.global.error;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * 드라이버·JPA·Spring 예외 계층을 API와 메트릭에 쓸 DB 실패 종류로 정규화한다.
 *
 * <p>MySQL lock timeout과 deadlock 코드는 cause chain의 {@link SQLException}에서 찾고, SQLState class {@code
 * 08}은 일시적인 연결 불가로 취급한다.
 */
public final class DatabaseFailureClassifier {

    private static final int MYSQL_LOCK_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;

    private DatabaseFailureClassifier() {}

    /** 가장 구체적인 DB 원인을 우선해 실패 유형을 반환한다. */
    public static Failure classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotGetJdbcConnectionException) {
                return Failure.UNAVAILABLE;
            }
            if (cause instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == MYSQL_DEADLOCK) {
                    return Failure.DEADLOCK;
                }
                if (sqlException.getErrorCode() == MYSQL_LOCK_TIMEOUT) {
                    return Failure.LOCK_TIMEOUT;
                }
                if (sqlException.getSQLState() != null
                        && sqlException.getSQLState().startsWith("08")) {
                    return Failure.UNAVAILABLE;
                }
            }
        }
        if (failure instanceof CannotAcquireLockException
                || failure instanceof PessimisticLockingFailureException
                || failure instanceof LockTimeoutException
                || failure instanceof PessimisticLockException) {
            return Failure.LOCK_TIMEOUT;
        }
        return Failure.UNCLASSIFIED;
    }

    public enum Failure {
        LOCK_TIMEOUT,
        DEADLOCK,
        UNAVAILABLE,
        UNCLASSIFIED
    }
}
