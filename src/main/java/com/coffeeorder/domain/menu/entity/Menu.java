package com.coffeeorder.domain.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 현재 판매 중인 메뉴 카탈로그 상태.
 *
 * <p>주문은 이 Entity를 참조해 과거 가격을 복원하지 않고, 결제 시점의 이름과 가격을 자체 snapshot으로 저장한다.
 */
@Entity
@Table(name = "menus")
public class Menu {

    @Id private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MenuStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Menu() {}

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public MenuStatus getStatus() {
        return status;
    }
}
