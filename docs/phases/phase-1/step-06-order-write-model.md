# Step 06. 주문·결제·Outbox 쓰기 모델

- PR 제목 예시: `feat: add order and outbox write model`
- branch 예시: `feature/phase1-step-06-order-model`
- 선행 step: Step 05

## 목표

주문 API의 트랜잭션을 작은 다음 PR에서 조립할 수 있도록 주문, 결제 원장 연결과 불변 `ORDER_PAID` Outbox 기록의 영속 모델을 먼저 완성한다.

## 구현 범위

- `Order`, `OrderStatus`, `OrderRepository`를 ERD의 snapshot·수량·금액·시각 계약대로 구현한다.
- 현재 `ACTIVE` 메뉴만 주문 가능하게 검증하고 결제 시점 이름과 가격 snapshot을 생성한다.
- Order Service가 `PAID` 주문을 먼저 저장해 ID를 반환하고, Point Service의 결제 원장 생성 API가 그 scalar `orderId`를 정확히 한 건만 참조하도록 서비스 책임을 구현한다.
- `OutboxEvent`, `OutboxStatus`, Repository와 `ORDER_PAID` payload 생성 기능을 구현한다.
- 한 번 캡처해 마이크로초 정밀도로 정규화한 `Instant`를 `orders.paid_at`과 이벤트 `occurredAt`에 함께 사용한다.
- 주문 저장과 Outbox 저장을 각각 자기 기능 Service에 두며, Facade가 Repository를 직접 호출할 필요가 없게 한다.
- Outbox의 최초 상태는 `PENDING`, `attempt_count = 0`, 마이크로초 정밀도로 정규화한 즉시 선점 가능 시각으로 설정한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/order/`
- `src/main/java/com/coffeeorder/domain/outbox/`
- 주문 가능 여부를 위한 메뉴 Service 확장
- `src/test/java/com/coffeeorder/domain/order/`
- `src/test/java/com/coffeeorder/domain/outbox/`

## 테스트

- 주문이 메뉴 ID, 이름, 단가, 수량 1, 결제 금액, `PAID` 상태를 snapshot으로 보존한다.
- 현재 비활성 메뉴는 주문 모델 생성 전에 거절된다.
- 결제 원장의 amount와 balance-after, order ID 연결이 정확하다.
- 주문을 저장해 얻은 ID가 Point Service에 scalar로 전달되고 같은 transaction의 `PAYMENT` 원장에 사용된다.
- 이벤트 payload가 API의 Phase 1 이벤트 계약과 정확히 일치한다.
- 나노초가 포함된 고정 `Clock`에서도 `paidAt`과 `occurredAt`이 동일한 마이크로초 정밀도 시각이다.
- 주문별 결제 원장과 주문별 `ORDER_PAID` 이벤트 중복이 DB 제약으로 거절된다.

## 수용 기준

- [ ] Entity가 현재 메뉴 가격 변화로 과거 주문 금액을 다시 계산하지 않는다.
- [ ] 저장 순서는 `PAID` 주문 ID 확보 후 scalar `orderId`를 사용한 `PAYMENT` 원장 생성이다.
- [ ] Outbox payload는 발행 시점이 아니라 주문 시점의 불변 snapshot이다.
- [ ] Service는 자기 기능 Repository만 직접 참조한다.
- [ ] 외부 HTTP 호출과 worker를 아직 추가하지 않는다.
- [ ] 미완성 주문 API를 공개하지 않는다.

## 제외 범위

- `POST /api/v1/orders`
- 전체 주문 Facade 트랜잭션
- Outbox 선점, lease, 재시도와 HTTP 전송
- 인기 메뉴 조회
