package com.coffeeorder.domain.menu.controller;

import com.coffeeorder.domain.menu.dto.GetMenusResponse;
import com.coffeeorder.domain.menu.service.MenuService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/menus")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping(produces = "application/json;charset=UTF-8")
    public GetMenusResponse getMenus() {
        return GetMenusResponse.from(menuService.getActiveMenus());
    }
}
