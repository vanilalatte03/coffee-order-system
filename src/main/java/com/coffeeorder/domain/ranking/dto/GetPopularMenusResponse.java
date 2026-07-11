package com.coffeeorder.domain.ranking.dto;

import com.coffeeorder.domain.ranking.service.PopularMenuRankingResult;
import java.time.Instant;
import java.util.List;

public record GetPopularMenusResponse(Instant from, Instant to, List<PopularMenuResponse> items) {

    public static GetPopularMenusResponse from(PopularMenuRankingResult result) {
        return new GetPopularMenusResponse(
                result.from(),
                result.to(),
                result.items().stream().map(PopularMenuResponse::from).toList());
    }
}
