package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.point.entity.PointBalanceOverflowException;
import com.coffeeorder.domain.point.entity.PointTransaction;
import com.coffeeorder.domain.point.entity.PointWallet;
import com.coffeeorder.domain.point.repository.PointTransactionRepository;
import com.coffeeorder.domain.point.repository.PointWalletRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PointWriteService {

    private final PointWalletRepository pointWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final Clock clock;
    private final EntityManager entityManager;

    public PointWriteService(
            PointWalletRepository pointWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            Clock clock,
            EntityManager entityManager) {
        this.pointWalletRepository = pointWalletRepository;
        this.pointTransactionRepository = pointTransactionRepository;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public long charge(long userId, long amount) {
        return chargeWithResult(userId, amount).balance();
    }

    @Transactional(
            propagation = Propagation.REQUIRED,
            noRollbackFor = PointBalanceOverflowException.class)
    public PointChargeResult chargeWithResult(long userId, long amount) {
        PointWallet wallet =
                pointWalletRepository
                        .findByUserIdForUpdate(userId)
                        .orElseThrow(() -> new PointWalletNotFoundException(userId));
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

    @Transactional(propagation = Propagation.REQUIRED)
    public PointPaymentPreparation preparePayment(long userId, long amount) {
        PointWallet wallet =
                pointWalletRepository
                        .findByUserIdForUpdate(userId)
                        .orElseThrow(() -> new PointWalletNotFoundException(userId));
        if (wallet.getBalance() < amount) {
            return PointPaymentPreparation.insufficient();
        }
        return PointPaymentPreparation.sufficient(new PointPaymentLock(wallet, amount));
    }

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
        PointWallet wallet =
                pointWalletRepository
                        .findByUserIdForUpdate(userId)
                        .orElseThrow(() -> new PointWalletNotFoundException(userId));
        Instant changedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long balanceAfter = balanceChange.apply(wallet, amount, changedAt);
        pointTransactionRepository.save(transactionFactory.apply(wallet, changedAt));
        return balanceAfter;
    }

    @FunctionalInterface
    private interface BalanceChange {
        long apply(PointWallet wallet, long amount, Instant changedAt);
    }
}
