# Step 12. Phase 1 통합 수용과 문서 동기화

- PR 제목 예시: `test: complete phase1 acceptance coverage`
- branch 예시: `feature/phase1-step-12-acceptance`
- 선행 step: Step 11

## 목표

새 기능을 추가하기보다 Phase 1 전체 보장을 실제 MySQL·HTTP stub·다중 thread 환경에서 증명하고, 문서를 구현 상태에 맞춰 최종 완료한다.

## 구현 범위

- PRD의 Phase 1 핵심 수용 시나리오와 아래 Step 12 전용 보장을 유스케이스 수준 통합 테스트로 묶는다.
- 주요 집계·Outbox query는 `INFORMATION_SCHEMA`로 인덱스 정의를 확인하고, 고정 fixture와 `ANALYZE TABLE` 뒤 `EXPLAIN FORMAT=JSON`으로 실행 계획을 확인한다.
- README의 현재 구현 상태, 실행 환경 변수, 테스트·실행 방법을 실제 코드에 맞춘다.
- PRD, Architecture, API, ERD, ADR의 구현 완료 여부와 코드 계약을 대조하고 필요한 최소 문서 수정을 같은 PR에 포함한다.
- 테스트가 드러낸 결함만 범위 안에서 수정하고 신규 기능을 추가하지 않는다.

## 필수 수용 시나리오

[PRD §8.1](../../PRD.md#81-phase-1)의 Phase 1 시나리오 전체를 검증한다. 동시 주문 20건 시나리오는 요청마다 서로 다른 멱등 키를 사용한다.

추가로 다음 Step 12 전용 보장을 검증한다.

- 같은 주문 요청을 같은 키로 동시에 보내도 주문·`PAYMENT` 원장·Outbox 효과가 각각 한 건이다.
- 서로 다른 키의 충전과 주문이 같은 지갑에서 경쟁해도 최종 잔액과 원장 합계가 일치한다.
- 비활성 메뉴 주문을 같은 키로 재전송하면 최초 상태와 안정적인 오류 payload를 재생하고 `timestamp`·`traceId`는 현재 요청 값으로 만든다.
- DB lock timeout 또는 일시적 DB 오류 뒤 같은 키로 재시도해도 중복 효과가 없다.

## 실행 계획 검증 규칙

- `INFORMATION_SCHEMA.STATISTICS`에서 `idx_orders_popular`, `idx_outbox_pending`, `idx_outbox_expired_lease`의 컬럼과 순서를 먼저 검증한다.
- 고정한 MySQL 이미지와 대표 고정 fixture에서 `ANALYZE TABLE`을 실행한 뒤 production query와 같은 조건으로 `EXPLAIN FORMAT=JSON`을 수집한다.
- 예상 인덱스가 `possible_keys`에 포함되는지를 재현 가능한 hard gate로 사용한다.
- optimizer가 실제 선택한 key는 데이터 규모와 판정 조건을 별도로 고정하지 않는 한 진단 정보로 기록하고 완료 여부를 가르는 hard gate로 사용하지 않는다.

## 수용 기준

- [ ] 네 API의 정상·오류·빈 결과 계약 테스트가 모두 통과한다.
- [ ] 단위, MySQL 통합, 동시성, 비동기 HTTP 실패 테스트가 안정적으로 통과한다.
- [ ] 집계·Outbox query의 인덱스 정의와 `possible_keys` hard gate가 고정한 MySQL fixture에서 통과한다.
- [ ] 로컬 JVM 락, H2, Redis를 정합성 근거로 사용하지 않는다.
- [ ] 외부 호출이 DB 락과 주문 transaction 밖에 있다.
- [ ] 로그와 설정에 secret, 전체 요청/응답, point balance가 노출되지 않는다.
- [ ] README와 정본 문서가 실제 dependency·실행법·동작과 일치한다.
- [ ] Kafka와 Redis 없이 Phase 1 완료 정의를 충족한다.
- [ ] 고정한 JDK·Gradle Wrapper·MySQL 이미지 버전, 공통 완료 게이트와 `bootRun`·네 API smoke 결과, 남은 제한사항과 Phase 2 이관 범위를 Step Issue에 기록한다.
- [ ] 미검증 항목이 없거나, 있다면 Phase 1 완료를 선언하지 않고 이유가 문서화된다.

## 제외 범위

- Kafka 발행과 Kafka Testcontainers
- Redis 메뉴 cache와 장애 fallback
- 성능 수치 목표를 근거 없이 확정하는 작업
- 신규 제품 기능 또는 관리자 API
