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

/**
 * MySQL 주문 원본으로 직전 168시간의 인기 활성 메뉴를 계산한다.
 *
 * <p>요청마다 UTC 시각을 한 번만 캡처해 half-open 기간을 고정한다. 인기 순위의 정합성 근거는 MySQL이며 Redis나 비동기 소비 결과를 사용하지 않는다.
 */
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

    /**
     * 한 번 캡처한 UTC 시각을 기준으로 {@code [to - 168시간, to)} 범위를 조회한다.
     *
     * <p>순위는 이미 정렬된 쿼리 결과의 위치로 정해져 동률일 때도 메뉴 ID 오름차순 계약을 유지한다.
     */
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
