package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.point.entity.PointTransaction;
import com.coffeeorder.domain.point.entity.PointWallet;
import com.coffeeorder.domain.point.repository.PointTransactionRepository;
import com.coffeeorder.domain.point.repository.PointWalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointWriteService {

    private final PointWalletRepository pointWalletRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final Clock clock;

    public PointWriteService(
            PointWalletRepository pointWalletRepository,
            PointTransactionRepository pointTransactionRepository,
            Clock clock) {
        this.pointWalletRepository = pointWalletRepository;
        this.pointTransactionRepository = pointTransactionRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public long charge(long userId, long amount) {
        return changeBalance(
                userId,
                amount,
                PointWallet::charge,
                (wallet, changedAt) ->
                        PointTransaction.charge(userId, amount, wallet.getBalance(), changedAt));
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
