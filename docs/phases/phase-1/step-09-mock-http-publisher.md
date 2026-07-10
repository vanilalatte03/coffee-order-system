# Step 09. Mock HTTP 비동기 발행기

- PR 제목 예시: `feat: publish order events over mock http`
- branch 예시: `feature/phase1-step-09-http-publisher`
- 선행 step: Step 08

## 목표

Outbox 처리 코어에 Phase 1 Mock HTTP 어댑터와 비동기 worker를 연결해 주문 commit과 외부 전송을 분리하면서 실제 실패·재시도 계약을 완성한다.

## 구현 범위

- `OrderEventPublisher`를 구현하는 HTTP adapter를 `infra`에 둔다.
- base URL, connect/read timeout, poll interval, lease와 worker ID를 설정으로 주입한다.
- `POST /api/v1/order-events`, `X-Event-Id`, JSON body 계약을 구현한다.
- 모든 `2xx`, timeout·네트워크 오류·429·5xx, 그 밖의 4xx를 문서대로 분류한다.
- scheduler가 1초 주기의 유실 방지 scan을 수행하게 한다.
- 주문 commit 뒤 local after-commit 신호로 첫 처리를 깨우되 이 신호에 전달 보장을 의존하지 않는다. scheduler와 신호는 production에서 같은 `runOneCycle` 처리 경계를 호출한다.
- worker는 선점 transaction을 끝낸 뒤 HTTP를 호출하고 별도 transaction에서 결과를 기록한다.
- 메트릭과 제한된 구조화 로그로 event ID, attempt, 결과와 지연을 관측할 수 있게 한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/infra/`
- `src/main/java/com/coffeeorder/domain/outbox/` worker·signal 연결
- `src/main/resources/application.yml`
- HTTP stub과 Awaitility 기반 테스트
- 새 실행 설정이 생기면 `README.md`

## 테스트

- HTTP 요청의 header와 JSON payload가 이벤트 계약과 일치한다.
- 모든 `2xx`가 `PUBLISHED`로 끝난다.
- timeout, 연결 실패, 429와 5xx가 다음 시각의 `PENDING`으로 예약된다.
- 재시도 불가능한 4xx가 즉시 `FAILED`로 격리된다.
- 계속 실패하면 자동 처리 한도 뒤 `FAILED`가 된다.
- 수신 성공 뒤 상태 기록 전 장애를 모사하면 같은 `eventId`가 중복 전송될 수 있다.
- 같은 `eventId`를 두 번 수신해도 Mock 수신자의 유니크 제약으로 이벤트를 한 번만 반영한다.
- 복수 worker에서도 이벤트 하나가 한 claim token으로만 정상 완료된다.
- 주문 API는 데이터 플랫폼 장애 중에도 이미 commit된 `201`을 유지한다.
- worker를 직접 호출하지 않고 주문 API만 호출해 after-commit 처리로 HTTP stub이 요청을 수신하고 Outbox가 완료되는지 검증한다.
- after-commit 신호를 억제해도 1초 주기 polling이 이벤트를 찾아 HTTP stub으로 전송하는지 검증한다.
- HTTP stub 응답을 block한 동안 실제 DB transaction과 지갑·Outbox row lock이 유지되지 않아 별도 connection의 DB 작업이 제한 시간 안에 완료되는지 검증한다.
- 11회 재시도 통합 테스트는 `MutableClock`, 결정적 jitter와 production과 같은 `runOneCycle` 경계를 사용한다. 매 cycle마다 `next_attempt_at`으로 시간을 전진해 짧고 제한된 Awaitility 조건 안에서 최종 `FAILED`를 검증한다.

## 수용 기준

- [ ] 정상 상태에서 주문 commit 후 1초 이내 첫 시도를 목표로 한다.
- [ ] HTTP 호출 동안 지갑 락과 Outbox row lock이 없다.
- [ ] 비동기 테스트가 `Thread.sleep()` 없이 제한된 Awaitility 조건을 사용한다.
- [ ] 비동기 wiring 테스트는 scheduler·after-commit의 production `runOneCycle` 경계를 우회해 worker를 직접 호출하지 않는다.
- [ ] 요청·응답 전문, point balance와 secret을 로그에 남기지 않는다.
- [ ] 설정 변경 뒤 실제 MySQL과 HTTP stub으로 `bootRun`·health를 확인한다.

## 제외 범위

- Kafka 발행 어댑터
- downstream 소비 완료 추적
- exactly-once 전달
- 운영자 UI
