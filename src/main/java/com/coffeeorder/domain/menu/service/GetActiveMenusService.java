package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.entity.MenuStatus;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetActiveMenusService {

    private final MenuRepository menuRepository;

    public GetActiveMenusService(MenuRepository menuRepository) {
        this.menuRepository = menuRepository;
    }

    @Transactional(readOnly = true)
    public List<ActiveMenuResult> getActiveMenus() {
        return menuRepository.findAllByStatusOrderByIdAsc(MenuStatus.ACTIVE).stream()
                .map(GetActiveMenusService::toResult)
                .toList();
    }

    private static ActiveMenuResult toResult(Menu menu) {
        return new ActiveMenuResult(menu.getId(), menu.getName(), menu.getPrice());
    }
}
