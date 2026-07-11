package com.coffeeorder.domain.user.service;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(long userId) {
        super("user not found: " + userId);
    }
}
