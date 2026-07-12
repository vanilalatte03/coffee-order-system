package com.coffeeorder.domain.ranking.service;

public record PopularMenuItemResult(
        int rank, long menuId, String name, long price, long orderCount) {}
