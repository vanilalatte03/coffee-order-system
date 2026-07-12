package com.coffeeorder.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.entity.MenuStatus;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("메뉴 서비스")
class MenuServiceTest {

    private final MenuRepository menuRepository = mock(MenuRepository.class);
    private final MenuService service = new MenuService(menuRepository);

    @DisplayName("활성 메뉴를 ID 오름차순 조회 계약으로 요청하고 결과로 변환한다")
    @Test
    void 활성_메뉴를_ID_오름차순_조회_계약으로_요청하고_결과로_변환한다() {
        Menu firstMenu = menu(1L, "아메리카노", 4500L, MenuStatus.ACTIVE);
        Menu secondMenu = menu(2L, "카페라떼", 5000L, MenuStatus.ACTIVE);
        when(menuRepository.findAllByStatusOrderByIdAsc(MenuStatus.ACTIVE))
                .thenReturn(List.of(firstMenu, secondMenu));

        List<ActiveMenuResult> results = service.getActiveMenus();

        assertThat(results)
                .containsExactly(
                        new ActiveMenuResult(1L, "아메리카노", 4500L),
                        new ActiveMenuResult(2L, "카페라떼", 5000L));
        verify(menuRepository).findAllByStatusOrderByIdAsc(MenuStatus.ACTIVE);
    }

    @DisplayName("활성 메뉴를 주문 가능한 서비스 결과로 변환한다")
    @Test
    void 활성_메뉴는_주문_가능한_서비스_결과로_변환한다() {
        Menu activeMenu = menu(2L, "카페라떼", 5000L, MenuStatus.ACTIVE);
        when(menuRepository.findById(2L)).thenReturn(Optional.of(activeMenu));

        OrderableMenuResult result = service.validateOrderable(2L);

        assertThat(result).isEqualTo(new OrderableMenuResult(2L, "카페라떼", 5000L));
        verify(menuRepository).findById(2L);
    }

    @DisplayName("없는 메뉴는 메뉴 없음 예외를 반환한다")
    @Test
    void 없는_메뉴는_메뉴_없음_예외를_반환한다() {
        when(menuRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateOrderable(999L))
                .isInstanceOf(MenuNotFoundException.class);
    }

    @DisplayName("비활성 메뉴는 주문 불가 예외를 반환한다")
    @Test
    void 비활성_메뉴는_주문_불가_예외를_반환한다() {
        Menu inactiveMenu = menu(4L, "품절 메뉴", 6000L, MenuStatus.INACTIVE);
        when(menuRepository.findById(4L)).thenReturn(Optional.of(inactiveMenu));

        assertThatThrownBy(() -> service.validateOrderable(4L))
                .isInstanceOf(MenuNotOrderableException.class);
    }

    @DisplayName("메뉴 조회와 주문 검증은 적절한 읽기 전용 트랜잭션 경계를 사용한다")
    @Test
    void 메뉴_조회와_주문_검증은_적절한_readOnly_트랜잭션_경계를_사용한다() throws NoSuchMethodException {
        Method getActiveMenus = MenuService.class.getMethod("getActiveMenus");
        Method validateOrderable = MenuService.class.getMethod("validateOrderable", long.class);

        Transactional activeMenusTransaction = getActiveMenus.getAnnotation(Transactional.class);
        Transactional orderableMenuTransaction =
                validateOrderable.getAnnotation(Transactional.class);

        assertThat(activeMenusTransaction).isNotNull();
        assertThat(activeMenusTransaction.readOnly()).isTrue();
        assertThat(orderableMenuTransaction).isNotNull();
        assertThat(orderableMenuTransaction.readOnly()).isTrue();
        assertThat(orderableMenuTransaction.noRollbackFor())
                .containsExactlyInAnyOrder(
                        MenuNotFoundException.class, MenuNotOrderableException.class);
    }

    private static Menu menu(long id, String name, long price, MenuStatus status) {
        Menu menu = mock(Menu.class);
        when(menu.getId()).thenReturn(id);
        when(menu.getName()).thenReturn(name);
        when(menu.getPrice()).thenReturn(price);
        when(menu.getStatus()).thenReturn(status);
        return menu;
    }
}
