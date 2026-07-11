package com.coffeeorder.domain.idempotency.service;

public record IdempotencyExecutionResult(IdempotencyResponseSnapshot snapshot, boolean replayed) {}
