package com.coffeeorder.domain.order.service;

public record CreatePaidOrderCommand(long userId, long menuId, String menuName, long unitPrice) {}
