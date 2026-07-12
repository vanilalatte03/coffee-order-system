package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.service.CanonicalPayload;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutionResult;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutor;
import com.coffeeorder.domain.idempotency.service.IdempotencyResponseSnapshot;
import com.coffeeorder.domain.point.dto.ChargePointsResponse;
import com.coffeeorder.domain.point.entity.PointBalanceOverflowException;
import com.coffeeorder.domain.user.service.UserService;
import com.coffeeorder.global.error.ErrorCode;
import com.coffeeorder.global.observability.RequestObservability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 포인트 충전의 멱등성 경계와 HTTP 응답 snapshot 생성을 조정한다.
 *
 * <p>이 클래스는 트랜잭션을 직접 열지 않는다. {@link IdempotencyExecutor}가 지갑 변경과 완료 결과 저장을 같은 트랜잭션으로 묶어, 재시도 시 중복
 * 충전 없이 최초 결과를 재생하게 한다.
 */
@Service
public class PointFacade {

    private static final Logger log = LoggerFactory.getLogger(PointFacade.class);

    private final IdempotencyExecutor idempotencyExecutor;
    private final UserService userService;
    private final PointWriteService pointWriteService;
    private final ObjectMapper objectMapper;

    public PointFacade(
            IdempotencyExecutor idempotencyExecutor,
            UserService userService,
            PointWriteService pointWriteService,
            ObjectMapper objectMapper) {
        this.idempotencyExecutor = idempotencyExecutor;
        this.userService = userService;
        this.pointWriteService = pointWriteService;
        this.objectMapper = objectMapper;
    }

    /**
     * 포인트를 충전하고 최초 결과 또는 동일 요청의 재생 결과를 반환한다.
     *
     * <p>잔액 오버플로는 도메인 변경 없이 저장되는 결정적 오류이며, 사용자 없음과 일시적 저장 실패는 멱등성 결과를 남기지 않는다.
     */
    public ChargePointsResult charge(
            ChargePointsCommand command, Instant responseTimestamp, String traceId) {
        CanonicalPayload payload =
                CanonicalPayload.fromJson(
                        "{\"amount\":"
                                + command.amount()
                                + ",\"userId\":"
                                + command.userId()
                                + "}");
        IdempotencyExecutionResult execution =
                idempotencyExecutor.execute(
                        command.userId(),
                        IdempotencyOperation.POINT_CHARGE,
                        command.idempotencyKey(),
                        payload,
                        () -> userService.validateExists(command.userId()),
                        () -> executeCharge(command));
        IdempotencyResponseSnapshot snapshot = execution.snapshot();
        String responseBody = snapshot.responseBody(responseTimestamp, traceId);
        log.info(
                "point_charge_completed traceId={} userId={} operation=POINT_CHARGE resultCode={} replayed={}",
                traceId,
                command.userId(),
                RequestObservability.resultCode(snapshot.responseStatus(), responseBody),
                execution.replayed());
        return new ChargePointsResult(
                snapshot.responseStatus(), responseBody, execution.replayed());
    }

    private IdempotencyResponseSnapshot executeCharge(ChargePointsCommand command) {
        try {
            PointChargeResult charged =
                    pointWriteService.chargeWithResult(command.userId(), command.amount());
            ChargePointsResponse response =
                    ChargePointsResponse.from(command.userId(), command.amount(), charged);
            return IdempotencyResponseSnapshot.success(201, writeJson(response));
        } catch (PointBalanceOverflowException exception) {
            return IdempotencyResponseSnapshot.deterministicError(ErrorCode.POINT_BALANCE_OVERFLOW);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "point charge response cannot be serialized", exception);
        }
    }
}
