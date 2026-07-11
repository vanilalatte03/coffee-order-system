package com.coffeeorder.domain.ranking.repository;

public record PopularMenuQueryRow(long menuId, String name, long price, long orderCount) {}
