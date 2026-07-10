# ADR-008: DB 멱등 응답과 요청별 오류 메타데이터 분리

- 상태: 채택됨
- 날짜: 2026-07-10
- 대체: [ADR-003](./003-idempotent-write-api.md)
- 관련: [`PRD` 멱등성](../PRD.md#63-멱등성), [`ARCHITECTURE` 멱등 처리](../ARCHITECTURE.md#53-멱등성-처리), [`API` 공통 규칙](../API.md#02-쓰기-요청-멱등성), [`ERD` 멱등 요청](../ERD.md#36-idempotency_requests)

## 맥락

ADR-003은 충전과 주문에 DB 기반 멱등성을 적용하고 같은 키·같은 요청에 최초 HTTP 상태와 본문을 재생하도록 결정했다. 이후 공통 오류 응답에 요청별 `timestamp`와 `traceId`가 포함되면서, 결정적 오류 body 전체를 그대로 저장하면 재생 요청의 trace ID가 현재 요청 로그와 일치하지 않는 충돌이 생겼다.

DB 유니크 제약, 요청 hash, 원자적 응답 snapshot이라는 핵심 결정은 유지하면서 성공 응답의 정확한 재생과 요청별 오류 추적을 함께 만족해야 한다.

## 검토한 대안

- **오류 body 전체를 그대로 재생** — 최초 응답과 byte 수준으로 같지만 재생 요청이 과거 `traceId`와 `timestamp`를 반환해 현재 요청 로그를 추적할 수 없다.
- **오류 body 전체를 매번 새로 생성** — 요청 추적은 정확하지만 코드나 메시지가 변경되면 같은 멱등 키의 비즈니스 결과가 달라질 수 있다.
- **안정적인 오류 payload와 요청 메타데이터 분리** — 최초 비즈니스 결과는 보존하고 `timestamp`와 `traceId`만 현재 요청에서 조립한다.

## 결정

충전과 주문의 멱등성 범위, hash, 저장 대상과 트랜잭션 원칙은 유지한다.

- 범위는 `(user_id, operation, idempotency_key)`이고 정규화 요청의 SHA-256 hash를 비교한다.
- 같은 키·같은 성공 요청은 최초 완료 HTTP 상태와 JSON body 전체를 그대로 재생한다.
- 결정적 비즈니스 오류는 최초 HTTP 상태와 안정적인 `code`, `message`, 선택적 `fieldErrors`를 저장·재생한다.
- 오류 envelope의 `timestamp`와 `traceId`는 snapshot에 저장하지 않고 최초 응답과 재생 응답을 만드는 현재 요청에서 생성한다. 응답 `traceId`는 해당 요청 로그와 일치해야 한다.
- 같은 키·다른 hash는 `409 IDEMPOTENCY_KEY_REUSED`로 거절한다.
- 구조 검증, 사용자 없음, DB·락·서버의 일시적 실패는 완료 결과로 저장하지 않는다.
- `PROCESSING`, 업무 쓰기와 `COMPLETED` snapshot은 같은 write transaction에 속하고, 유니크 충돌 후 재조회는 transaction 밖의 새 read transaction에서 수행한다.

세부 HTTP 필드와 저장 대상 오류는 [API 공통 규칙](../API.md#02-쓰기-요청-멱등성)을 정본으로 따른다.

## 결과

- 성공 응답은 최초 주문 ID, 잔액과 시각을 정확히 재생한다.
- 결정적 오류의 비즈니스 의미는 고정하면서 각 재생 요청을 현재 `traceId`로 추적할 수 있다.
- 멱등 저장 모델은 성공 body와 오류 payload를 구분해 응답 envelope를 조립해야 한다.
- 클라이언트는 결정적 오류의 `timestamp`와 `traceId`가 최초 응답과 달라질 수 있음을 계약으로 인지해야 한다.
