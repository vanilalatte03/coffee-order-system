package com.coffeeorder.domain.ranking.service;

import java.time.Instant;
import java.util.List;

public record PopularMenuRankingResult(
        Instant from, Instant to, List<PopularMenuItemResult> items) {}
