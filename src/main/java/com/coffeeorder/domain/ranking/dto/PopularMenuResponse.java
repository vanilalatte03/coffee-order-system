package com.coffeeorder.domain.ranking.dto;

import com.coffeeorder.domain.ranking.service.PopularMenuItemResult;

public record PopularMenuResponse(int rank, long menuId, String name, long price, long orderCount) {

    static PopularMenuResponse from(PopularMenuItemResult result) {
        return new PopularMenuResponse(
                result.rank(), result.menuId(), result.name(), result.price(), result.orderCount());
    }
}
