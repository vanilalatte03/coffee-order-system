package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.entity.MenuStatus;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {

    private final MenuRepository menuRepository;

    public MenuService(MenuRepository menuRepository) {
        this.menuRepository = menuRepository;
    }

    @Transactional(readOnly = true)
    public List<ActiveMenuResult> getActiveMenus() {
        return menuRepository.findAllByStatusOrderByIdAsc(MenuStatus.ACTIVE).stream()
                .map(MenuService::toActiveMenuResult)
                .toList();
    }

    @Transactional(
            readOnly = true,
            noRollbackFor = {MenuNotFoundException.class, MenuNotOrderableException.class})
    public OrderableMenuResult validateOrderable(long menuId) {
        Menu menu =
                menuRepository
                        .findById(menuId)
                        .orElseThrow(() -> new MenuNotFoundException(menuId));
        if (menu.getStatus() != MenuStatus.ACTIVE) {
            throw new MenuNotOrderableException(menuId);
        }
        return new OrderableMenuResult(menu.getId(), menu.getName(), menu.getPrice());
    }

    private static ActiveMenuResult toActiveMenuResult(Menu menu) {
        return new ActiveMenuResult(menu.getId(), menu.getName(), menu.getPrice());
    }
}
