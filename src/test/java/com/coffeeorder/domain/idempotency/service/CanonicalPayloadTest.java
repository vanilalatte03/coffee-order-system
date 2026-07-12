package com.coffeeorder.domain.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeeorder.global.error.ErrorCode;
import com.coffeeorder.global.observability.RequestObservability;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CanonicalPayloadTest {

    @Test
    void 같은_JSON은_공백과_필드_순서에_관계없이_같은_hash를_만든다() {
        CanonicalPayload first = CanonicalPayload.fromJson("{\"amount\":100,\"userId\":10}");
        CanonicalPayload second =
                CanonicalPayload.fromJson("{ \"userId\" : 10, \"amount\" : 100 }");

        assertThat(first.json()).isEqualTo("{\"amount\":100,\"userId\":10}");
        assertThat(second.sha256()).isEqualTo(first.sha256()).hasSize(64);
    }

    @Test
    void 결정적_오류는_안정_payload만_저장하고_현재_메타데이터를_조립한다() {
        IdempotencyResponseSnapshot snapshot =
                IdempotencyResponseSnapshot.deterministicError(ErrorCode.INSUFFICIENT_POINTS);

        assertThat(snapshot.storedBody())
                .isEqualTo("{\"code\":\"INSUFFICIENT_POINTS\",\"message\":\"포인트 잔액이 부족합니다.\"}");
        String firstResponseBody =
                snapshot.responseBody(Instant.parse("2026-07-11T01:02:03Z"), "first-trace");
        String replayResponseBody =
                snapshot.responseBody(Instant.parse("2026-07-11T01:02:04Z"), "replay-trace");

        assertThat(firstResponseBody)
                .isEqualTo(
                        "{\"code\":\"INSUFFICIENT_POINTS\",\"message\":\"포인트 잔액이 부족합니다.\",\"timestamp\":\"2026-07-11T01:02:03Z\",\"traceId\":\"first-trace\"}");
        assertThat(replayResponseBody)
                .isEqualTo(
                        "{\"code\":\"INSUFFICIENT_POINTS\",\"message\":\"포인트 잔액이 부족합니다.\",\"timestamp\":\"2026-07-11T01:02:04Z\",\"traceId\":\"replay-trace\"}");
        assertThat(snapshot.storedBody()).doesNotContain("timestamp", "traceId");
        assertThat(RequestObservability.resultCode(snapshot.responseStatus(), replayResponseBody))
                .isEqualTo(ErrorCode.INSUFFICIENT_POINTS.code());
    }

    @Test
    void 결정적_오류_snapshot에_요청별_메타데이터를_저장할_수_없다() {
        assertThatThrownBy(
                        () ->
                                IdempotencyResponseSnapshot.deterministicError(
                                        409, "{\"code\":\"ERROR\",\"traceId\":\"past-trace\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
