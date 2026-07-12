package com.coffeeorder.global.error;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

public final class DatabaseFailureClassifier {

    private static final int MYSQL_LOCK_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;

    private DatabaseFailureClassifier() {}

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
