package com.coffeeorder.domain.menu.service;

public class MenuNotFoundException extends RuntimeException {

    public MenuNotFoundException(long menuId) {
        super("menu not found: " + menuId);
    }
}
