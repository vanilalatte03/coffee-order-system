package com.coffeeorder.domain.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "point_wallets")
public class PointWallet {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PointWallet() {}

    PointWallet(long userId, long balance, Instant createdAt, Instant updatedAt) {
        if (balance < 0) {
            throw new IllegalArgumentException("point balance must not be negative");
        }
        this.userId = userId;
        this.balance = balance;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public long charge(long amount, Instant changedAt) {
        validatePositive(amount);

        final long chargedBalance;
        try {
            chargedBalance = Math.addExact(balance, amount);
        } catch (ArithmeticException exception) {
            throw new PointBalanceOverflowException(exception);
        }

        balance = chargedBalance;
        updatedAt = Objects.requireNonNull(changedAt);
        return balance;
    }

    public long pay(long amount, Instant changedAt) {
        validatePositive(amount);
        if (balance < amount) {
            throw new InsufficientPointBalanceException(balance, amount);
        }

        balance -= amount;
        updatedAt = Objects.requireNonNull(changedAt);
        return balance;
    }

    public long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    private static void validatePositive(long amount) {
        if (amount <= 0) {
            throw new InvalidPointAmountException(amount);
        }
    }
}
