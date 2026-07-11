package com.coffeeorder.domain.outbox.service;

public interface OrderEventPublisher {

    OrderEventPublishResult publish(ClaimedOrderEvent event);
}
