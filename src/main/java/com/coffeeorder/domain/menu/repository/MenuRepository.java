package com.coffeeorder.domain.menu.repository;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.entity.MenuStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findAllByStatusOrderByIdAsc(MenuStatus status);
}
