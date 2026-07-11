package com.coffeeorder.domain.menu.controller;

import com.coffeeorder.domain.menu.dto.GetMenusResponse;
import com.coffeeorder.domain.menu.service.GetActiveMenusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/menus")
public class MenuController {

    private final GetActiveMenusService getActiveMenusService;

    public MenuController(GetActiveMenusService getActiveMenusService) {
        this.getActiveMenusService = getActiveMenusService;
    }

    @GetMapping(produces = "application/json;charset=UTF-8")
    public GetMenusResponse getMenus() {
        return GetMenusResponse.from(getActiveMenusService.getActiveMenus());
    }
}
