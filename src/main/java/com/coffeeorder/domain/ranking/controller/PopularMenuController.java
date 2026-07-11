package com.coffeeorder.domain.ranking.controller;

import com.coffeeorder.domain.ranking.dto.GetPopularMenusResponse;
import com.coffeeorder.domain.ranking.service.RankingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/menus/popular")
public class PopularMenuController {

    private final RankingService rankingService;

    public PopularMenuController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping(produces = "application/json;charset=UTF-8")
    public GetPopularMenusResponse getPopularMenus() {
        return GetPopularMenusResponse.from(rankingService.getPopularMenus());
    }
}
