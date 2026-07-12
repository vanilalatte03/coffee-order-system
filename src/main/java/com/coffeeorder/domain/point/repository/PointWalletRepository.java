package com.coffeeorder.domain.point.repository;

import com.coffeeorder.domain.point.entity.PointWallet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointWalletRepository extends JpaRepository<PointWallet, Long> {

    /**
     * 포인트 쓰기 동안 유지할 사용자 지갑 행의 비관적 쓰기 잠금을 획득한다.
     *
     * <p>충전과 결제 모두 이 조회를 통해 같은 잠금 순서를 사용해야 다중 인스턴스에서도 잔액과 원장이 일관되게 변경된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from PointWallet wallet where wallet.userId = :userId")
    Optional<PointWallet> findByUserIdForUpdate(@Param("userId") long userId);
}
