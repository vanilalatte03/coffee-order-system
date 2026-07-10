# Step 07. 멱등 주문·결제 API

- PR 제목 예시: `feat: add atomic order payment api`
- branch 예시: `feature/phase1-step-07-order-payment`
- 선행 step: Step 06

## 목표

`POST /api/v1/orders`와 `OrderFacade`를 구현해 주문, 포인트 차감, 결제 원장, Outbox, 멱등 결과를 하나의 원자적 유스케이스로 완성한다.

## 구현 범위

- 주문 Request/Response DTO, Command/Result와 Controller를 구현하고, 주문 Command의 path와 body를 canonical payload로 정규화해 Step 04의 공통 hasher로 `request_hash`를 만든다.
- `OrderFacade`는 자체를 외부 `@Transactional`로 감싸지 않고 멱등 실행, 사용자 확인 사전 검증과 원자적 업무 callback을 조정한다. `IdempotencyExecutor`가 사전 검증 뒤 별도 proxied runner 또는 `TransactionTemplate`로 물리 write transaction을 실행·commit하고 유니크 충돌 후 새 read transaction 재조회를 담당한다.
- callback 안의 정본 순서는 `메뉴 검증 → 지갑 락·잔액 검증 → PAID 주문 저장·ID 확보 → 지갑 차감·PAYMENT(orderId) 원장 → Outbox → 멱등 완료 snapshot → commit`으로 고정한다.
- Facade는 각 기능 Service만 조합하고 Repository를 직접 참조하지 않는다.
- 충분한 잔액이면 `201`과 주문 snapshot, 남은 잔액을 반환한다.
- 메뉴 없음·비활성, 잔액 부족은 도메인 쓰기 없이 안정적인 비즈니스 오류 payload만 멱등 snapshot으로 커밋한다. 요청별 `traceId`와 오류 발생 시각은 저장하지 않는다.
- 사용자 없음, 저장 실패와 일시적 서버 오류는 멱등 기록을 포함해 롤백한다.
- 외부 데이터 플랫폼의 상태와 무관하게 주문 commit이 API 성공 기준임을 유지한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/order/controller/`
- `src/main/java/com/coffeeorder/domain/order/dto/`
- `src/main/java/com/coffeeorder/domain/order/service/OrderFacade.java`
- 관련 기능 Service의 최소 확장
- 공통 오류 매핑과 대응 테스트

## 테스트

- 충분한 잔액 주문은 지갑 감소, `PAID` 주문, `PAYMENT` 원장, Outbox, 완료 멱등 행을 각각 한 건 만든다.
- 응답의 메뉴·금액·잔액·상태·시각이 최초 commit snapshot과 일치한다.
- 같은 키·같은 주문은 최초 응답을 재생하고 추가 효과가 없다.
- 같은 키·다른 메뉴는 `409 IDEMPOTENCY_KEY_REUSED`이다.
- 메뉴 없음·비활성, 잔액 부족은 주문·원장·Outbox 없이 해당 오류 snapshot만 남긴다.
- 사용자 없음에는 멱등 행도 남지 않는다.
- Outbox 저장을 강제로 실패시키면 주문, 지갑 차감, 원장과 멱등 행이 모두 롤백된다.
- 주문·지갑 차감·`PAYMENT` 원장·Outbox 쓰기 뒤 `COMPLETED` snapshot flush를 강제로 실패시키면 모든 도메인 쓰기와 `PROCESSING` 행이 롤백되고, 같은 키 재시도는 주문·원장·Outbox 효과를 각각 정확히 한 건만 만든다.
- 클라이언트가 가격 등 알 수 없는 필드를 보내면 `400`이다.

## 수용 기준

- [ ] 지갑 락을 보유한 동안 외부 네트워크를 호출하지 않는다.
- [ ] 주문 관련 원자적 쓰기가 하나의 트랜잭션에 참여한다.
- [ ] `OrderFacade` 바깥에 write transaction이 없고 하위 Service는 Executor가 연 transaction에 `REQUIRED`로 참여한다.
- [ ] 주문 저장으로 ID를 확보한 뒤 지갑 차감과 `PAYMENT(orderId)` 원장을 기록한다.
- [ ] 결정적 실패를 예외로 던져 transaction을 rollback-only로 만들지 않는다.
- [ ] 결정적 오류 snapshot은 요청별 `traceId`와 오류 발생 시각을 제외한 안정적 비즈니스 payload다.
- [ ] 주문 성공은 데이터 플랫폼 수신 성공을 의미하지 않는다.
- [ ] API와 오류 code가 `docs/API.md`와 일치한다.

## 제외 범위

- Outbox 비동기 발행
- 주문 취소·환불·복수 메뉴
- 주문 조회 API
- Kafka 발행
