package com.coffeeorder.domain.outbox.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** lease 만료와 백오프 시간을 실제 대기 없이 이동시키는 Outbox 단위 테스트용 Clock. */
final class MutableClock extends Clock {

    private volatile Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void set(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
