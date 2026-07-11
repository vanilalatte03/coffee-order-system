package com.coffeeorder.domain.order.service;

public record CreateOrderResult(int status, String responseBody, boolean replayed) {}
