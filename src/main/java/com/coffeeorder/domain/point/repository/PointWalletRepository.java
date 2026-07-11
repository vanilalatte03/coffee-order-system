package com.coffeeorder.domain.point.repository;

import com.coffeeorder.domain.point.entity.PointWallet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointWalletRepository extends JpaRepository<PointWallet, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from PointWallet wallet where wallet.userId = :userId")
    Optional<PointWallet> findByUserIdForUpdate(@Param("userId") long userId);
}
