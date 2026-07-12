package com.coffeeorder.domain.point.repository;

import com.coffeeorder.domain.point.entity.PointTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {}
