# Step 11. 관측성과 런타임 오류 하드닝

- PR 제목 예시: `feat: add phase1 operational signals`
- branch 예시: `feature/phase1-step-11-observability`
- 선행 step: Step 10

## 목표

Phase 1의 핵심 보장을 운영 중 확인할 수 있도록 구조화 로그, 메트릭과 안정적인 인프라 오류 변환을 완성한다.

## 구현 범위

- API request 수·지연·오류율을 endpoint와 결과 code 수준에서 확인한다.
- 지갑 lock 대기 시간, lock timeout과 deadlock 분류를 계측한다.
- 멱등 replay·key conflict 수를 operation별로 계측한다.
- Outbox `PENDING`·`PROCESSING`·`FAILED` 수, 최고 대기 시간, 첫 전송 지연과 retry 수를 계측한다.
- 인기 메뉴 query 지연을 계측한다.
- trace ID, user/order/event ID, operation과 결과 code를 필요한 범위에서 구조화 로그에 남긴다.
- DB 연결 실패·lock timeout·분류되지 않은 오류를 API의 안정적인 `503`/`500` 계약으로 변환한다.
- 메트릭 조회가 업무 transaction과 Outbox 선점을 방해하지 않도록 별도 read 경계를 유지한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/global/`
- 각 기능 Service의 최소 계측 지점
- `src/main/resources/application.yml`
- 관측성과 오류 변환 테스트

## 테스트

- 정상·비즈니스 거절·서버 오류 응답에 같은 요청의 trace ID가 유지된다.
- 멱등 최초 처리, replay와 conflict가 서로 다른 counter로 기록된다.
- DB lock timeout은 `503 CONCURRENCY_TIMEOUT`과 가능한 경우 `Retry-After: 1`을 반환한다.
- DB 연결 실패는 내부 SQL 없이 `503 DATABASE_UNAVAILABLE`로 변환된다.
- Outbox 성공·retry·격리 뒤 gauge/counter가 실제 DB 상태와 일치한다.
- 로그에 요청·응답 전문, point balance, idempotency snapshot과 secret이 포함되지 않는다.

## 수용 기준

- [ ] 예상 가능한 비즈니스 거절을 `ERROR`로 남발하지 않는다.
- [ ] label에 무제한 user/order/event ID를 넣어 고카디널리티 메트릭을 만들지 않는다.
- [ ] 운영 식별자는 로그에, 집계 가능한 상태와 결과는 메트릭에 둔다.
- [ ] 계측 실패가 업무 성공·실패 결과를 바꾸지 않는다.
- [ ] Actuator 노출 범위에 민감한 endpoint를 임의로 추가하지 않는다.

## 제외 범위

- 외부 모니터링 SaaS와 dashboard 구축
- 구체적인 alert threshold 확정
- 분산 tracing backend 도입
- Kafka·Redis 메트릭
