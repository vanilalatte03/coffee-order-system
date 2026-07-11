package com.coffeeorder.domain.menu.dto;

import com.coffeeorder.domain.menu.service.ActiveMenuResult;

public record MenuResponse(long menuId, String name, long price) {

    public static MenuResponse from(ActiveMenuResult result) {
        return new MenuResponse(result.menuId(), result.name(), result.price());
    }
}
