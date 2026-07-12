package com.coffeeorder.domain.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "point_transactions")
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointTransactionType type;

    @Column(nullable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PointTransaction() {}

    private PointTransaction(
            long userId,
            Long orderId,
            PointTransactionType type,
            long amount,
            long balanceAfter,
            Instant createdAt) {
        validatePositive(amount);
        if (balanceAfter < 0) {
            throw new IllegalArgumentException("point balance must not be negative");
        }
        if (type == PointTransactionType.CHARGE && orderId != null) {
            throw new IllegalArgumentException("charge transaction must not have order id");
        }
        if (type == PointTransactionType.PAYMENT && (orderId == null || orderId <= 0)) {
            throw new IllegalArgumentException("payment transaction requires order id");
        }
        this.userId = userId;
        this.orderId = orderId;
        this.type = Objects.requireNonNull(type);
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static PointTransaction charge(
            long userId, long amount, long balanceAfter, Instant createdAt) {
        return new PointTransaction(
                userId, null, PointTransactionType.CHARGE, amount, balanceAfter, createdAt);
    }

    public static PointTransaction payment(
            long userId, long orderId, long amount, long balanceAfter, Instant createdAt) {
        return new PointTransaction(
                userId, orderId, PointTransactionType.PAYMENT, amount, balanceAfter, createdAt);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public PointTransactionType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private static void validatePositive(long amount) {
        if (amount <= 0) {
            throw new InvalidPointAmountException(amount);
        }
    }
}
