package com.coffeeorder.domain.idempotency.service;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("idempotency key was already used for another request");
    }
}
