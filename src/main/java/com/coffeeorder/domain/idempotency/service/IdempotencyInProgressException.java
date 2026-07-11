package com.coffeeorder.domain.idempotency.service;

public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException() {
        super("idempotency request is still processing");
    }
}
