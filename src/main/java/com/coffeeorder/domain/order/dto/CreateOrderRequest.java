package com.coffeeorder.domain.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotNull(message = "필수 값입니다.") @Positive(message = "1 이상의 정수여야 합니다.") Long userId,
        @NotNull(message = "필수 값입니다.") @Positive(message = "1 이상의 정수여야 합니다.") Long menuId) {}
