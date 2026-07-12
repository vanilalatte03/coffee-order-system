package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.point.entity.PointBalanceOverflowException;
import com.coffeeorder.domain.point.entity.PointTransaction;
import com.coffeeorder.domain.point.entity.PointWallet;
import com.coffeeorder.domain.point.repository.PointTransactionRepository;
import com.coffeeorder.domain.point.repository.PointWalletRepository;
import com.coffeeorder.global.error.DatabaseFailureClassifier;
import com.coffeeorder.global.observability.OperationalMetrics;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * MySQL 지갑 행 잠금 아래에서 포인트 잔액과 원장을 함께 변경한다.
 *
 * <p>모든 쓰기 경로는 {@code point_wallets.user_id}를 먼저 비관적으로 잠가 같은 사용자의 충전과 결제를 직렬화한다. 외부 호출은 이 서비스의 트랜잭션
 * 안에서 수행하지 않는다.
 */
@Service
public class PointWriteService {

    private final PointWalletRepository pointWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final Clock clock;
    private final EntityManager entityManager;
    private final OperationalMetrics metrics;

    public PointWriteService(
            PointWalletRepository pointWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            Clock clock,
            EntityManager entityManager,
            OperationalMetrics metrics) {
        this.pointWalletRepository = pointWalletRepository;
        this.pointTransactionRepository = pointTransactionRepository;
        this.clock = clock;
        this.entityManager = entityManager;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public long charge(long userId, long amount) {
        return chargeWithResult(userId, amount).balance();
    }

    /**
     * 지갑 잠금, 잔액 증가, 충전 원장을 하나의 트랜잭션으로 처리한다.
     *
     * <p>오버플로는 Facade가 재생 가능한 결정적 오류 snapshot으로 바꾸므로 rollback-only로 만들지 않는다. 이를 통해 같은 멱등성 트랜잭션에서 오류
     * 결과를 {@code COMPLETED}로 저장할 수 있다.
     */
    @Transactional(
            propagation = Propagation.REQUIRED,
            noRollbackFor = PointBalanceOverflowException.class)
    public PointChargeResult chargeWithResult(long userId, long amount) {
        PointWallet wallet = lockWallet(userId, "charge");
        Instant changedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long balance = wallet.charge(amount, changedAt);
        PointTransaction transaction =
                pointTransactionRepository.saveAndFlush(
                        PointTransaction.charge(userId, amount, balance, changedAt));
        return new PointChargeResult(transaction.getId(), balance, changedAt);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public long pay(long userId, long orderId, long amount) {
        return changeBalance(
                userId,
                amount,
                PointWallet::pay,
                (wallet, changedAt) ->
                        PointTransaction.payment(
                                userId, orderId, amount, wallet.getBalance(), changedAt));
    }

    /**
     * 주문 가격만큼 지갑을 잠근 뒤 잔액 충분 여부와 일회용 완료 권한을 반환한다.
     *
     * <p>이 단계는 잔액을 바꾸지 않는다. 반환한 {@link PointPaymentLock}은 반드시 같은 트랜잭션에서 주문 ID를 얻은 뒤 {@link
     * #completePayment(PointPaymentLock, long, long)}로 소비해야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PointPaymentPreparation preparePayment(long userId, long amount) {
        PointWallet wallet = lockWallet(userId, "payment");
        if (wallet.getBalance() < amount) {
            return PointPaymentPreparation.insufficient();
        }
        return PointPaymentPreparation.sufficient(new PointPaymentLock(wallet, amount));
    }

    /**
     * 이미 잠긴 지갑을 한 번만 차감하고 결제 원장을 주문 ID와 연결한다.
     *
     * <p>잠금을 획득한 영속 엔티티가 현재 트랜잭션에 계속 연결돼 있는지 확인해, 다른 트랜잭션이나 스레드에서 이전 잠금 권한을 재사용하는 것을 막는다.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public long completePayment(PointPaymentLock paymentLock, long expectedUserId, long orderId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("payment completion requires an active transaction");
        }
        if (paymentLock.userId() != expectedUserId) {
            throw new IllegalArgumentException("payment lock belongs to another user");
        }
        if (!entityManager.contains(paymentLock.wallet())) {
            throw new IllegalStateException(
                    "payment lock must be completed in the transaction that acquired it");
        }
        Instant changedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        PointWallet wallet = paymentLock.consume(expectedUserId);
        long balance = wallet.pay(paymentLock.amount(), changedAt);
        pointTransactionRepository.saveAndFlush(
                PointTransaction.payment(
                        wallet.getUserId(), orderId, paymentLock.amount(), balance, changedAt));
        return balance;
    }

    private long changeBalance(
            long userId,
            long amount,
            BalanceChange balanceChange,
            BiFunction<PointWallet, Instant, PointTransaction> transactionFactory) {
        PointWallet wallet = lockWallet(userId, "balance_change");
        Instant changedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long balanceAfter = balanceChange.apply(wallet, amount, changedAt);
        pointTransactionRepository.save(transactionFactory.apply(wallet, changedAt));
        return balanceAfter;
    }

    private PointWallet lockWallet(long userId, String operation) {
        long startedAt = System.nanoTime();
        try {
            return pointWalletRepository
                    .findByUserIdForUpdate(userId)
                    .orElseThrow(() -> new PointWalletNotFoundException(userId));
        } catch (RuntimeException exception) {
            DatabaseFailureClassifier.Failure failure =
                    DatabaseFailureClassifier.classify(exception);
            if (failure == DatabaseFailureClassifier.Failure.LOCK_TIMEOUT
                    || failure == DatabaseFailureClassifier.Failure.DEADLOCK) {
                metrics.increment(
                        "coffee.wallet.lock.failures",
                        "operation",
                        operation,
                        "type",
                        failure.name().toLowerCase());
            }
            throw exception;
        } finally {
            metrics.record(
                    "coffee.wallet.lock.wait",
                    Duration.ofNanos(System.nanoTime() - startedAt),
                    "operation",
                    operation);
        }
    }

    @FunctionalInterface
    private interface BalanceChange {
        long apply(PointWallet wallet, long amount, Instant changedAt);
    }
}
