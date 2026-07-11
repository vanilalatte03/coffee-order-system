package com.coffeeorder.domain.order.entity;

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
@Table(name = "orders")
public class Order {

    private static final int QUANTITY = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "menu_id", nullable = false)
    private long menuId;

    @Column(name = "menu_name_snapshot", nullable = false, length = 100)
    private String menuNameSnapshot;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "paid_amount", nullable = false)
    private long paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Order() {}

    private Order(
            long userId, long menuId, String menuNameSnapshot, long unitPrice, Instant paidAt) {
        if (userId <= 0 || menuId <= 0) {
            throw new IllegalArgumentException("user id and menu id must be positive");
        }
        if (menuNameSnapshot == null || menuNameSnapshot.isBlank()) {
            throw new IllegalArgumentException("menu name snapshot must not be blank");
        }
        if (unitPrice <= 0) {
            throw new IllegalArgumentException("unit price must be positive");
        }
        this.userId = userId;
        this.menuId = menuId;
        this.menuNameSnapshot = menuNameSnapshot;
        this.unitPrice = unitPrice;
        this.quantity = QUANTITY;
        this.paidAmount = unitPrice;
        this.status = OrderStatus.PAID;
        this.paidAt = Objects.requireNonNull(paidAt);
        this.createdAt = paidAt;
    }

    public static Order paid(
            long userId, long menuId, String menuNameSnapshot, long unitPrice, Instant paidAt) {
        return new Order(userId, menuId, menuNameSnapshot, unitPrice, paidAt);
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public long getMenuId() {
        return menuId;
    }

    public String getMenuNameSnapshot() {
        return menuNameSnapshot;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getPaidAmount() {
        return paidAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
