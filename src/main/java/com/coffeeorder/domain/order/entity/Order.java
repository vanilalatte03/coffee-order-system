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

/**
 * 결제 완료된 주문의 불변 결제 snapshot.
 *
 * <p>Phase 1은 수량 1개만 지원하며, 결제 시점의 메뉴 이름·단가와 동일한 금액을 저장한다. 이후 현재 메뉴의 이름이나 가격이 바뀌어도 과거 주문을 다시 계산하지
 * 않는다.
 */
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

    /**
     * 수량 1개로 즉시 결제 완료된 주문을 만든다.
     *
     * <p>호출자는 주문 가능 여부를 검증한 현재 메뉴의 이름과 가격을 넘기며, 이 값은 이후 메뉴 변경과 독립적으로 보존된다.
     */
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
