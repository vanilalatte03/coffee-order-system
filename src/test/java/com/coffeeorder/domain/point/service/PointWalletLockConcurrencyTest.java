package com.coffeeorder.domain.point.service;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.MySqlIntegrationTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PointWalletLockConcurrencyTest extends MySqlIntegrationTestSupport {

    @Autowired private PointWriteService pointWriteService;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetPointData() {
        jdbcTemplate.update("DELETE FROM point_transactions");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("UPDATE point_wallets SET balance = 0, updated_at = UTC_TIMESTAMP(6)");
    }

    @Test
    void 같은_지갑의_쓰기는_별도_connection과_transaction에서_직렬화된다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        Future<Void> lockHolder =
                executor.submit(
                        () -> {
                            holdWalletLock(10, startBarrier, lockAcquired, releaseLock);
                            return null;
                        });
        Future<Long> blockedCharge =
                executor.submit(
                        () -> {
                            startBarrier.await(5, SECONDS);
                            assertThat(lockAcquired.await(5, SECONDS)).isTrue();
                            return pointWriteService.charge(10, 100);
                        });

        try {
            assertThat(lockAcquired.await(5, SECONDS)).isTrue();
            assertThatThrownBy(() -> blockedCharge.get(300, MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();

            assertThat(blockedCharge.get(5, SECONDS)).isEqualTo(100);
            lockHolder.get(5, SECONDS);
            assertThat(balanceOf(10)).isEqualTo(100);
            assertThat(ledgerCountFor(10)).isEqualTo(1);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 서로_다른_사용자_지갑은_독립적으로_변경된다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        Future<Void> lockHolder =
                executor.submit(
                        () -> {
                            holdWalletLock(10, startBarrier, lockAcquired, releaseLock);
                            return null;
                        });
        Future<Long> independentCharge =
                executor.submit(
                        () -> {
                            startBarrier.await(5, SECONDS);
                            assertThat(lockAcquired.await(5, SECONDS)).isTrue();
                            return pointWriteService.charge(20, 200);
                        });

        try {
            assertThat(lockAcquired.await(5, SECONDS)).isTrue();
            assertThat(independentCharge.get(2, SECONDS)).isEqualTo(200);
            assertThat(lockHolder).isNotDone();

            releaseLock.countDown();

            lockHolder.get(5, SECONDS);
            assertThat(balanceOf(10)).isZero();
            assertThat(balanceOf(20)).isEqualTo(200);
            assertThat(ledgerCountFor(20)).isEqualTo(1);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private void holdWalletLock(
            long userId,
            CyclicBarrier startBarrier,
            CountDownLatch lockAcquired,
            CountDownLatch releaseLock)
            throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            startBarrier.await(5, SECONDS);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT balance FROM point_wallets WHERE user_id = ? FOR UPDATE")) {
                statement.setLong(1, userId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    lockAcquired.countDown();
                    assertThat(releaseLock.await(5, SECONDS)).isTrue();
                }
            }
            connection.commit();
        }
    }

    private long balanceOf(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM point_wallets WHERE user_id = ?", Long.class, userId);
    }

    private int ledgerCountFor(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", Integer.class, userId);
    }
}
