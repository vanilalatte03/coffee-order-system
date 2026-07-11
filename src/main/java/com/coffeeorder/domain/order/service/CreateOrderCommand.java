package com.coffeeorder.domain.order.service;

public record CreateOrderCommand(long userId, long menuId, String idempotencyKey) {}
