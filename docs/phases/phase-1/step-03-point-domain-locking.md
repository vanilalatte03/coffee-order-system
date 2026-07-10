# Step 03. 포인트 도메인과 비관적 락

- PR 제목 예시: `feat: add locked point wallet domain`
- branch 예시: `feature/phase1-step-03-point-locking`
- 선행 step: Step 02

## 목표

공개 충전 API를 열기 전에 사용자, 지갑, 불변 원장과 실제 MySQL 비관적 락 기반의 포인트 쓰기 기능을 완성한다.

## 구현 범위

- `User`, `PointWallet`, `PointTransaction`, `PointTransactionType` Entity와 Repository를 구현한다.
- Step 03에는 아직 `Order` Entity가 없으므로 `PointTransaction.orderId`는 nullable scalar FK로 매핑하고 `Order` 객체 연관을 만들지 않는다. `CHARGE`에는 `null`, 이후 `PAYMENT`에는 저장된 주문 ID를 사용한다.
- 지갑을 `SELECT ... FOR UPDATE`로 조회하는 의도가 드러나는 Repository 메서드를 구현한다.
- `PointWallet`에 양수 충전, `long` 오버플로 방지, 충분한 잔액 결제 정책을 구현한다.
- 충전 직후와 결제 차감 직후의 `balance_after`를 남기는 append-only 원장 생성 책임을 구현한다. 결제 원장 생성 API는 상위 유스케이스가 전달한 scalar `orderId`를 요구한다.
- 사용자 존재 확인과 포인트 기능을 각각 자기 기능 Service에 둔다.
- 포인트 쓰기 Service가 상위 트랜잭션에 참여할 수 있도록 하고 외부 호출이나 로컬 락을 넣지 않는다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/point/`
- 필요한 최소 사용자 조회 기능 패키지
- `src/test/java/com/coffeeorder/domain/point/`

## 테스트

- 1P 충전과 일반 충전이 잔액과 `CHARGE` 원장에 정확히 반영된다.
- 0·음수 충전이 도메인에서 거절된다.
- `Long.MAX_VALUE` 경계의 충전 오버플로가 부분 변경 없이 거절된다.
- 충분한 잔액은 결제되고 부족한 잔액은 변경되지 않는다.
- 실제 MySQL의 같은 지갑 행 쓰기가 서로 다른 트랜잭션에서 직렬화된다.
- 서로 다른 사용자 지갑은 독립적으로 처리할 수 있다.
- DB 제약이 잘못된 원장 유형·order ID 조합을 최종 방어선에서 거절한다.

## 수용 기준

- [ ] 포인트 현재 잔액과 불변 원장의 책임이 분리되어 있다.
- [ ] `PointTransaction.orderId`가 Step 06의 `Order` Entity를 선행 의존하지 않는 nullable scalar FK다.
- [ ] 모든 포인트 쓰기는 같은 지갑 락 획득 경로를 사용한다.
- [ ] Entity에는 범용 setter 대신 의도가 드러나는 변경 메서드가 있다.
- [ ] 동시성 검증은 별도 connection·트랜잭션과 제한 시간을 사용한다.
- [ ] 아직 멱등성이 없는 공개 충전 API를 노출하지 않는다.

## 제외 범위

- `POST /points/charges`
- 멱등 요청 저장과 응답 재생
- 주문 Entity와 Outbox
- lock timeout HTTP 매핑
