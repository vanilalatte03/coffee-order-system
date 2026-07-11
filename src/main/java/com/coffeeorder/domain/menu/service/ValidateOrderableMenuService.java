package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.entity.MenuStatus;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidateOrderableMenuService {

    private final MenuRepository menuRepository;

    public ValidateOrderableMenuService(MenuRepository menuRepository) {
        this.menuRepository = menuRepository;
    }

    @Transactional(readOnly = true)
    public OrderableMenuResult validate(long menuId) {
        Menu menu =
                menuRepository
                        .findById(menuId)
                        .orElseThrow(() -> new MenuNotFoundException(menuId));
        if (menu.getStatus() != MenuStatus.ACTIVE) {
            throw new MenuNotOrderableException(menuId);
        }
        return new OrderableMenuResult(menu.getId(), menu.getName(), menu.getPrice());
    }
}
