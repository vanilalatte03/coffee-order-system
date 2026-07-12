package com.coffeeorder.domain.idempotency.service;

@FunctionalInterface
public interface IdempotencyWork {

    IdempotencyResponseSnapshot execute();
}
