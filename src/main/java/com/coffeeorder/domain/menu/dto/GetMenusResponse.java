package com.coffeeorder.domain.menu.dto;

import com.coffeeorder.domain.menu.service.ActiveMenuResult;
import java.util.List;

public record GetMenusResponse(List<MenuResponse> items) {

    public static GetMenusResponse from(List<ActiveMenuResult> menus) {
        return new GetMenusResponse(menus.stream().map(MenuResponse::from).toList());
    }
}
