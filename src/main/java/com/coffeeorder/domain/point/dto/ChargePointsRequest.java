package com.coffeeorder.domain.point.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChargePointsRequest(
        @NotNull(message = "충전액은 필수입니다.") @Positive(message = "1 이상의 정수여야 합니다.") Long amount) {}
