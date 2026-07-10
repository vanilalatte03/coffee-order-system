# Step 04. DB 기반 멱등성 실행기

- PR 제목 예시: `feat: add database idempotency executor`
- branch 예시: `feature/phase1-step-04-idempotency`
- 선행 step: Step 03

## 목표

충전과 주문이 공유할 멱등성 실행 경계를 구현해 재시도, 키 재사용 충돌, 동시 선점과 트랜잭션 롤백을 DB 기준으로 처리한다.

## 구현 범위

- `IdempotencyRequest`, operation/status enum, Repository와 응답 snapshot 모델을 구현한다.
- 범위 `(userId, operation, idempotencyKey)`와 키 형식 `^[A-Za-z0-9._:-]{1,128}$`을 적용한다.
- 충전과 주문 Command를 안정적으로 정규화하고 SHA-256 hash를 만드는 기능을 구현한다.
- 완료 기록이 있으면 같은 hash의 성공은 저장된 status/body를 그대로 재생하고, 결정적 오류는 저장된 status와 안정적인 오류 payload에 현재 요청의 `timestamp`·`traceId`를 조립한다. 다른 hash는 키 재사용 충돌로 분류한다.
- 기록이 없으면 업무 트랜잭션 안에서 `PROCESSING`을 선점하고 성공 또는 결정적 실패를 `COMPLETED`로 저장한다.
- 호출 Service나 Facade 자체를 외부 `@Transactional`로 감싸지 않고, `IdempotencyExecutor`가 별도 proxied runner 또는 `TransactionTemplate`로 `PROCESSING`, 업무 callback과 `COMPLETED` snapshot flush를 포함한 물리 write transaction을 실행·commit한다.
- commit 또는 flush의 유니크 충돌은 write transaction이 종료된 뒤 포착하고, rollback-only 상태 밖의 새 read transaction에서 승자 결과를 재조회한다. callback의 하위 Service는 기본 `REQUIRED`로 같은 write transaction에 참여한다.
- 일시적 인프라 실패와 저장 대상인 결정적 비즈니스 실패를 구분하는 결과 모델을 만든다.
- 결정적 오류 snapshot에는 안정적인 비즈니스 payload만 저장하고 요청별 `traceId`와 오류 발생 시각은 포함하지 않는다.
- `COMPLETED` snapshot flush 실패는 callback의 모든 도메인 쓰기와 `PROCESSING` 행을 함께 롤백한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/idempotency/`
- `src/test/java/com/coffeeorder/domain/idempotency/`

## 테스트

- 같은 범위·같은 hash의 성공은 최초 HTTP status와 JSON body를 그대로 재생한다.
- 같은 범위·다른 hash는 `IDEMPOTENCY_KEY_REUSED`로 분류한다.
- operation이 다르면 같은 키 문자열을 독립적으로 사용할 수 있다.
- 동시 선점에서 유니크 승자는 한 건이고 후속 실행은 완료 결과를 재생한다.
- 콜백의 일시적 예외가 업무 변경과 `PROCESSING` 행을 모두 롤백한다.
- 결정적 실패는 도메인 쓰기 없이 오류 snapshot만 커밋할 수 있다.
- `COMPLETED` snapshot flush를 강제로 실패시키면 callback의 업무 변경과 `PROCESSING` 행이 모두 롤백되고, 같은 키 재시도는 업무 효과를 정확히 한 번만 만든다.
- 최초 응답 뒤 다른 업무 상태가 바뀌어도 성공 snapshot과 결정적 오류의 안정적인 비즈니스 payload는 변하지 않는다.
- 결정적 오류를 재생해도 저장 body에는 요청별 `traceId`와 오류 발생 시각이 없고 비즈니스 payload만 동일하다.

## 수용 기준

- [ ] 멱등성은 인스턴스 메모리가 아닌 MySQL 유니크 제약을 최종 기준으로 삼는다.
- [ ] 호출 Service와 Facade 바깥에 write transaction이 없고, Executor가 물리 transaction의 commit·충돌 포착을 담당한다.
- [ ] 트랜잭션 self-invocation이나 rollback-only 상태에서 재조회하지 않는다.
- [ ] 사용자 없음과 구조 검증 실패에는 멱등 행이 남지 않는다.
- [ ] V1에서 완료 기록 자동 만료를 구현하지 않는다.
- [ ] 특정 Controller DTO에 결합된 범용 프레임워크로 과도하게 확장하지 않는다.

## 제외 범위

- 충전·주문 Controller 연결
- 업무별 오류 HTTP 응답
- 멱등 기록 정리 배치
- Redis 기반 멱등성
