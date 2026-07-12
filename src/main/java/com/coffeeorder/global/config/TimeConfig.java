package com.coffeeorder.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DB와 API의 절대 시각 계산에 사용할 UTC Clock을 제공한다.
 *
 * <p>서비스는 이 Clock에서 시각을 한 번 캡처한 뒤 MySQL {@code DATETIME(6)} 정밀도로 정규화한다.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
