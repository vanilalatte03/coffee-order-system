package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.entity.MenuStatus;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 메뉴 카탈로그의 조회와 주문 가능 여부 검증을 담당한다.
 *
 * <p>주문용 검증 오류는 상위 멱등성 유스케이스가 완료 오류 snapshot으로 전환할 수 있도록 rollback-only로 만들지 않는다.
 */
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

    /**
     * 주문 직전에 메뉴 존재 여부와 현재 활성 상태를 검증한다.
     *
     * <p>이 예외는 상위 주문 유스케이스에서 재생 가능한 결정적 오류로 변환된다.
     */
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
