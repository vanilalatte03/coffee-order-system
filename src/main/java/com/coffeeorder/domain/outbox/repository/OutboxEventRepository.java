package com.coffeeorder.domain.outbox.repository;

import com.coffeeorder.domain.outbox.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {}
