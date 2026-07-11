package com.coffeeorder.domain.ranking.service;

import com.coffeeorder.domain.ranking.repository.PopularMenuQueryRepository;
import com.coffeeorder.domain.ranking.repository.PopularMenuQueryRow;
import com.coffeeorder.global.observability.OperationalMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RankingService {

    private static final Duration RANKING_PERIOD = Duration.ofHours(168);

    private final Clock clock;
    private final PopularMenuQueryRepository popularMenuQueryRepository;
    private final OperationalMetrics metrics;

    public RankingService(
            Clock clock,
            PopularMenuQueryRepository popularMenuQueryRepository,
            OperationalMetrics metrics) {
        this.clock = clock;
        this.popularMenuQueryRepository = popularMenuQueryRepository;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public PopularMenuRankingResult getPopularMenus() {
        Instant to = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant from = to.minus(RANKING_PERIOD);
        long startedAt = System.nanoTime();
        List<PopularMenuQueryRow> rows;
        try {
            rows = popularMenuQueryRepository.findPopularMenus(from, to);
        } finally {
            metrics.record(
                    "coffee.ranking.query.duration",
                    Duration.ofNanos(System.nanoTime() - startedAt),
                    "query",
                    "popular_menus");
        }
        List<PopularMenuItemResult> items = new ArrayList<>(rows.size());

        for (int index = 0; index < rows.size(); index++) {
            PopularMenuQueryRow row = rows.get(index);
            items.add(
                    new PopularMenuItemResult(
                            index + 1, row.menuId(), row.name(), row.price(), row.orderCount()));
        }

        return new PopularMenuRankingResult(from, to, List.copyOf(items));
    }
}
