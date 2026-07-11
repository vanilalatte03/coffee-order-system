package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.service.CanonicalPayload;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutionResult;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutor;
import com.coffeeorder.domain.idempotency.service.IdempotencyResponseSnapshot;
import com.coffeeorder.domain.point.dto.ChargePointsResponse;
import com.coffeeorder.domain.point.entity.PointBalanceOverflowException;
import com.coffeeorder.domain.user.service.ValidateUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ChargePointsService {

    private static final String OVERFLOW_BODY =
            "{\"code\":\"POINT_BALANCE_OVERFLOW\",\"message\":\"포인트 잔액이 범위를 초과합니다.\"}";

    private final IdempotencyExecutor idempotencyExecutor;
    private final ValidateUserService validateUserService;
    private final PointWriteService pointWriteService;
    private final ObjectMapper objectMapper;

    public ChargePointsService(
            IdempotencyExecutor idempotencyExecutor,
            ValidateUserService validateUserService,
            PointWriteService pointWriteService,
            ObjectMapper objectMapper) {
        this.idempotencyExecutor = idempotencyExecutor;
        this.validateUserService = validateUserService;
        this.pointWriteService = pointWriteService;
        this.objectMapper = objectMapper;
    }

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
                        () -> validateUserService.validateExists(command.userId()),
                        () -> executeCharge(command));
        IdempotencyResponseSnapshot snapshot = execution.snapshot();
        return new ChargePointsResult(
                snapshot.responseStatus(),
                snapshot.responseBody(responseTimestamp, traceId),
                execution.replayed());
    }

    private IdempotencyResponseSnapshot executeCharge(ChargePointsCommand command) {
        try {
            PointChargeResult charged =
                    pointWriteService.chargeWithResult(command.userId(), command.amount());
            ChargePointsResponse response =
                    ChargePointsResponse.from(command.userId(), command.amount(), charged);
            return IdempotencyResponseSnapshot.success(201, writeJson(response));
        } catch (PointBalanceOverflowException exception) {
            return IdempotencyResponseSnapshot.deterministicError(422, OVERFLOW_BODY);
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
