# Step 08. Outbox 선점·lease·재시도 코어

- PR 제목 예시: `feat: add outbox delivery state machine`
- branch 예시: `feature/phase1-step-08-outbox-core`
- 선행 step: Step 07

## 목표

네트워크 어댑터와 분리된 Outbox 처리 코어를 구현해 여러 인스턴스의 작업자가 같은 테이블에서 이벤트를 안전하게 선점하고 성공·재시도·격리 결과를 기록할 수 있게 한다.

## 구현 범위

- `PENDING`과 `attempt_count < 11`인 lease 만료 `PROCESSING` 후보를 `FOR UPDATE SKIP LOCKED`로 선점한다.
- 짧은 트랜잭션에서 `PROCESSING`, 증가한 `attempt_count`, 새 `claim_token`, `locked_by`, `locked_until`을 저장하고 commit한다.
- `attempt_count = 11`인 lease 만료 `PROCESSING` 행은 12회째 선점하거나 외부 호출하지 않고 짧은 트랜잭션에서 `FAILED`로 전이하며 claim·lease를 정리한다.
- 결과 갱신은 `event_id + PROCESSING + claim_token` 조건부 update로 수행한다.
- 성공 시 `PUBLISHED`, 실패 분류에 따라 다음 `PENDING` 또는 `FAILED` 상태로 전이한다.
- 최초 dispatch 뒤 최대 10회 재선점, 총 최대 11회라는 한도를 구현한다.
- 지수 backoff, jitter 범위와 300초 cap을 순수 정책으로 구현해 고정 가능한 시간·난수로 테스트한다.
- `OrderEventPublisher` port와 전송 결과 분류 모델을 주문/Outbox 기능 경계에 둔다.
- `FAILED`만 새 자동 처리 주기로 되돌릴 수 있는 조건부 원자적 reset 기능을 제공하되 관리자 HTTP API는 만들지 않는다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/outbox/repository/`
- `src/main/java/com/coffeeorder/domain/outbox/service/`
- 필요한 외부 발행 port
- `src/test/java/com/coffeeorder/domain/outbox/`

## 테스트

- 여러 작업자가 같은 후보를 동시에 선점해도 한 claim만 성공한다.
- 여러 후보는 `SKIP LOCKED`로 서로 다른 작업자에게 분배될 수 있다.
- `attempt_count < 11`인 lease 만료 `PROCESSING` 행은 횟수를 증가시키고 새 token으로 회수된다.
- `attempt_count = 11`인 작업자가 선점 commit 후 종료하면 lease 만료 뒤 외부 발행 port를 호출하지 않고 `FAILED`가 되며 count는 11, claim·lock 필드는 `NULL`로 남는다.
- 이전 token의 늦은 성공·실패 결과는 새 상태를 덮어쓰지 못한다.
- 성공은 claim/lease를 비우고 `PUBLISHED`와 발행 시각을 기록한다.
- 발행 port의 재시도 가능 결과는 backoff 뒤 `PENDING`, 영구 실패 결과는 즉시 `FAILED`가 된다.
- 11번째 선점 실패 뒤에는 추가 자동 선점 없이 `FAILED`가 된다.
- jitter와 cap이 문서 범위를 벗어나지 않는다.
- reset은 `FAILED` 행에만 성공하고 `attempt_count = 0`, `next_attempt_at = 현재 UTC`, `claim_token = NULL`, `locked_by = NULL`, `locked_until = NULL`, `status = PENDING`을 한 트랜잭션에서 반영한다.
- 같은 `FAILED` 행을 동시에 reset하면 조건부 update 한 건만 성공한다.
- reset 전 token의 늦은 성공·실패 결과는 0-row update로 무시된다.

## 수용 기준

- [ ] 외부 호출 중 DB transaction이나 row lock을 유지할 필요가 없는 API로 분리된다.
- [ ] `attempt_count` 의미가 dispatch 선점 횟수와 일치한다.
- [ ] crash recovery를 포함해 `attempt_count`가 11을 넘지 않으며 한도 도달 행에는 외부 호출이 없다.
- [ ] claim token을 잃은 작업자의 결과가 0-row update로 무시된다.
- [ ] `FAILED` reset이 상태 조건과 초기화 필드를 하나의 원자적 update로 보장한다.
- [ ] 이벤트를 자동 삭제하지 않고 실패 원인을 제한된 길이로 보존한다.
- [ ] exactly-once 보장을 주장하지 않는다.

## 제외 범위

- 실제 HTTP 호출
- scheduler와 after-commit wake-up
- Kafka
- 관리자용 재처리 API 또는 UI
