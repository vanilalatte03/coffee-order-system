package com.coffeeorder.domain.menu.service;

public class MenuNotOrderableException extends RuntimeException {

    public MenuNotOrderableException(long menuId) {
        super("menu is not orderable: " + menuId);
    }
}
