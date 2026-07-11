package com.coffeeorder.domain.point.service;

public record ChargePointsCommand(long userId, long amount, String idempotencyKey) {}
