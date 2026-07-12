package com.coffeeorder.domain.point.service;

import com.coffeeorder.domain.idempotency.entity.IdempotencyOperation;
import com.coffeeorder.domain.idempotency.service.CanonicalPayload;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutionResult;
import com.coffeeorder.domain.idempotency.service.IdempotencyExecutor;
import com.coffeeorder.domain.idempotency.service.IdempotencyResponseSnapshot;
import com.coffeeorder.domain.point.dto.ChargePointsResponse;
import com.coffeeorder.domain.point.entity.PointBalanceOverflowException;
import com.coffeeorder.domain.user.service.ValidateUserService;
import com.coffeeorder.global.error.ErrorCode;
import com.coffeeorder.global.observability.RequestObservability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChargePointsService {

    private static final Logger log = LoggerFactory.getLogger(ChargePointsService.class);

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
