package com.coffeeorder.domain.menu.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeeorder.MySqlIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class MenuApiIntegrationTest extends MySqlIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 활성_메뉴만_ID_오름차순으로_정확한_API_필드를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/menus"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(
                        content()
                                .json(
                                        """
                                        {
                                          "items": [
                                            {"menuId": 1, "name": "아메리카노", "price": 4500},
                                            {"menuId": 2, "name": "카페라떼", "price": 5000},
                                            {"menuId": 3, "name": "카푸치노", "price": 5500}
                                          ]
                                        }
                                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    @Transactional
    void 활성_메뉴가_없으면_빈_items를_반환한다() throws Exception {
        jdbcTemplate.update("UPDATE menus SET status = 'INACTIVE'");

        mockMvc.perform(get("/api/v1/menus"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[]}", JsonCompareMode.STRICT));
    }
}
