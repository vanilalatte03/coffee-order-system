# Step 05. 멱등 포인트 충전 API

- PR 제목 예시: `feat: add idempotent point charge api`
- branch 예시: `feature/phase1-step-05-point-charge`
- 선행 step: Step 04

## 목표

`POST /api/v1/users/{userId}/points/charges`를 포인트 락과 DB 멱등성에 연결해 외부에서 사용할 수 있는 첫 쓰기 API를 완성한다.

## 구현 범위

- 충전 Request/Response DTO, Command/Result, Controller와 유스케이스 Service를 구현한다.
- path, body, `Idempotency-Key`의 구조 검증과 API 오류 매핑을 구현한다.
- 충전 Command의 path와 body를 canonical payload로 정규화하고 Step 04의 공통 hasher로 `request_hash`를 만든다.
- 충전 유스케이스 Service 자체를 외부 `@Transactional`로 감싸지 않고, `IdempotencyExecutor`에 사용자 확인 사전 검증과 지갑 락, 잔액 증가, `CHARGE` 원장, 완료 응답 snapshot 업무 callback을 위임한다. Executor는 사전 검증 뒤 물리 write transaction을 실행·commit한다.
- 최초 응답은 `201`과 `Idempotency-Replayed: false`, 재생 응답은 저장한 body와 `true`를 반환한다.
- `POINT_BALANCE_OVERFLOW`는 도메인 변경 없이 안정적인 비즈니스 오류 payload만 멱등 결과로 저장하고 요청별 `traceId`와 오류 발생 시각은 snapshot에 포함하지 않는다.
- DB 락 대기 timeout을 `503 CONCURRENCY_TIMEOUT`과 가능한 경우 `Retry-After: 1`로 변환한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/point/controller/`
- `src/main/java/com/coffeeorder/domain/point/dto/`
- `src/main/java/com/coffeeorder/domain/point/service/`
- 공통 오류 매핑의 필요한 확장
- 대응 테스트 경로

## 테스트

- 정상 충전은 잔액과 원장을 한 번 증가시키고 계약과 같은 `201` body를 반환한다.
- 같은 키·같은 금액 재전송은 원장을 늘리지 않고 최초 body를 재생한다.
- 최초 응답 뒤 주문 등으로 잔액이 달라져도 재생 `balance`는 바뀌지 않는다.
- 같은 키·다른 금액은 `409 IDEMPOTENCY_KEY_REUSED`이다.
- 알 수 없는 사용자는 `404`이고 지갑·원장·멱등 기록이 없다.
- 오버플로는 `422`이며 같은 키 재전송에 저장된 오류를 재생한다.
- 같은 키의 동시 요청 20건은 충전과 원장을 정확히 한 건만 만든다.
- 잔액·`CHARGE` 원장 쓰기 뒤 `COMPLETED` snapshot flush를 강제로 실패시키면 잔액, 원장과 `PROCESSING` 행이 모두 롤백되고, 같은 키 재시도는 충전과 원장을 정확히 한 건만 만든다.
- 잘못된 header/path/body는 `400`이고 멱등 기록이 없다.

## 수용 기준

- [ ] API 필드, status, header와 오류 code가 `docs/API.md`와 일치한다.
- [ ] 같은 사용자에 대한 모든 충전이 지갑 행 락을 거친다.
- [ ] Controller는 트랜잭션이나 Repository를 소유하지 않는다.
- [ ] 성공과 결정적 실패 snapshot이 각각 정확한 시점에 커밋된다.
- [ ] 충전 유스케이스 Service 바깥에 write transaction이 없고 하위 Service는 Executor가 연 transaction에 `REQUIRED`로 참여한다.
- [ ] 결정적 오류 snapshot은 요청별 `traceId`와 오류 발생 시각을 제외한 안정적 비즈니스 payload다.
- [ ] timeout·DB 장애·분류되지 않은 오류에는 멱등 완료 결과가 남지 않는다.

## 제외 범위

- 주문·결제 API
- 포인트 조회 API
- 충전 취소, 상한, 만료
- Outbox 이벤트
