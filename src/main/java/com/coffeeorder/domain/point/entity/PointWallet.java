package com.coffeeorder.domain.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * 사용자별 현재 포인트 잔액을 보유하는 지갑 aggregate.
 *
 * <p>잔액은 음수가 될 수 없고, 변경 전에는 서비스 계층이 사용자 행의 비관적 쓰기 잠금을 획득해야 한다. 변경 근거는 별도의 {@link PointTransaction}
 * 원장으로 남긴다.
 */
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

    /** 양수 금액을 더하고 long 범위 초과를 도메인 오류로 변환한다. */
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

    /** 잔액이 충분할 때만 양수 금액을 차감한다. */
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
