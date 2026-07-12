package com.coffeeorder.domain.menu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.entity.MenuStatus;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class GetActiveMenusServiceTest {

    private final MenuRepository menuRepository = mock(MenuRepository.class);
    private final GetActiveMenusService service = new GetActiveMenusService(menuRepository);

    @Test
    void 활성_메뉴를_ID_오름차순_조회_계약으로_요청하고_결과로_변환한다() {
        Menu firstMenu = menu(1L, "아메리카노", 4500L);
        Menu secondMenu = menu(2L, "카페라떼", 5000L);
        when(menuRepository.findAllByStatusOrderByIdAsc(MenuStatus.ACTIVE))
                .thenReturn(List.of(firstMenu, secondMenu));

        List<ActiveMenuResult> results = service.getActiveMenus();

        assertThat(results)
                .containsExactly(
                        new ActiveMenuResult(1L, "아메리카노", 4500L),
                        new ActiveMenuResult(2L, "카페라떼", 5000L));
        verify(menuRepository).findAllByStatusOrderByIdAsc(MenuStatus.ACTIVE);
    }

    @Test
    void 메뉴_조회는_readOnly_트랜잭션을_사용한다() throws NoSuchMethodException {
        Method method = GetActiveMenusService.class.getMethod("getActiveMenus");

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    private static Menu menu(long id, String name, long price) {
        Menu menu = mock(Menu.class);
        when(menu.getId()).thenReturn(id);
        when(menu.getName()).thenReturn(name);
        when(menu.getPrice()).thenReturn(price);
        return menu;
    }
}
