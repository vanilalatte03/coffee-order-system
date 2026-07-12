# 커피 주문 시스템 API 명세

- 문서 상태: 설계 확정
- 작성일: 2026-07-10
- Base URL: `/api/v1`
- 관련 문서: [PRD](./PRD.md), [아키텍처](./ARCHITECTURE.md), [ERD](./ERD.md)

## 0. 공통 규칙

### 0.1 프로토콜과 형식

- 요청과 응답은 `application/json; charset=UTF-8`을 사용한다.
- ID와 포인트·가격은 JSON 정수이며 Java `long` 범위 안에 있어야 한다.
- 금액에 소수, 문자열, `0`, 음수를 사용할 수 없다.
- 시각은 RFC 3339 UTC 형식으로 반환한다. 예: `2026-07-10T04:30:00.123Z`.
- 알 수 없는 JSON 필드와 잘못된 타입은 `400 VALIDATION_ERROR`로 거절한다.
- 인증·인가는 V1 범위가 아니다.

### 0.2 쓰기 요청 멱등성

포인트 충전과 주문·결제는 다음 헤더가 필수다.

```http
Idempotency-Key: 9b87ce50-7583-4b54-8e50-cf7237030198
```

- 허용 형식: `^[A-Za-z0-9._:-]{1,128}$`
- 권장 값: 요청마다 새 UUID
- 범위: `(userId, operation, Idempotency-Key)`
- 같은 키·같은 성공 요청: 최초 완료 HTTP 상태와 JSON 본문을 그대로 반환
- 같은 키·같은 결정적 오류 요청: 최초 HTTP 상태와 안정적인 비즈니스 오류 payload인 `code`, `message`, 선택적 `fieldErrors`를 재생하고, 요청 메타데이터인 `timestamp`와 `traceId`는 재생 요청마다 새로 생성
- 같은 키·다른 요청: `409 IDEMPOTENCY_KEY_REUSED`
- 재생 응답 헤더: `Idempotency-Replayed: true`
- 최초 응답 헤더: `Idempotency-Replayed: false`
- 저장 대상: `201`과 실행 후 확정된 `MENU_NOT_FOUND`, `MENU_NOT_ORDERABLE`, `INSUFFICIENT_POINTS`, `POINT_BALANCE_OVERFLOW`
- 저장 제외: 구조 검증 실패, `USER_NOT_FOUND`, DB·락·분류되지 않은 서버 오류
- 보존 기간: V1에서는 자동 만료시키지 않는다.

멱등 결과로 저장된 비즈니스 실패는 이후 메뉴나 잔액 상태가 달라져도 최초 HTTP 상태와 안정적인 비즈니스 오류 payload를 재생한다. 재생 응답의 `timestamp`와 `traceId`는 현재 요청 값이며 저장된 최초 값이 아니다. 조건을 해결한 뒤 유스케이스를 다시 실행하려면 새 키를 사용한다.

동시에 같은 키가 도착하면 DB 유니크 제약의 승자 트랜잭션이 완료될 때까지 후속 요청이 대기한 뒤 결과를 재생한다. 대기가 DB lock timeout을 넘으면 `503 CONCURRENCY_TIMEOUT`이며 클라이언트는 같은 키로 재시도할 수 있다.

### 0.3 공통 오류 응답

```json
{
  "timestamp": "2026-07-10T04:30:00.123Z",
  "traceId": "01J2G1K0PV8A3QF28M5V4HFXYB",
  "code": "VALIDATION_ERROR",
  "message": "요청 값이 올바르지 않습니다.",
  "fieldErrors": [
    {
      "field": "amount",
      "reason": "1 이상의 정수여야 합니다."
    }
  ]
}
```

`fieldErrors`는 필드 오류가 있을 때만 포함한다. `timestamp`와 `traceId`는 요청별 메타데이터이며, `traceId`는 해당 응답을 만든 현재 요청 로그의 식별자와 일치한다. 결정적 비즈니스 오류를 멱등 재생할 때도 안정적인 비즈니스 오류 payload만 최초 결과에서 재생하고 두 메타데이터는 현재 요청에서 새로 생성한다. 내부 예외 메시지, SQL, stack trace는 응답하지 않는다.

## 1. 메뉴 목록 조회

현재 주문 가능한 메뉴를 ID 오름차순으로 조회한다.

```http
GET /api/v1/menus
```

### 성공 응답

`200 OK`

```json
{
  "items": [
    {
      "menuId": 1,
      "name": "아메리카노",
      "price": 4500
    },
    {
      "menuId": 2,
      "name": "카페라떼",
      "price": 5000
    }
  ]
}
```

활성 메뉴가 없으면 `200 OK`와 빈 `items`를 반환한다. `INACTIVE` 메뉴는 노출하지 않는다. Phase 2의 Redis 장애나 캐시 미스는 내부적으로 MySQL 폴백하므로 API 계약을 바꾸지 않는다.

## 2. 포인트 충전

사전 등록된 사용자의 지갑에 포인트를 충전한다.

```http
POST /api/v1/users/{userId}/points/charges
Idempotency-Key: 9b87ce50-7583-4b54-8e50-cf7237030198
Content-Type: application/json
```

### Path parameter

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `userId` | positive `long` | Y | 사전 등록된 사용자 ID |

### 요청 본문

```json
{
  "amount": 10000
}
```

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `amount` | `long` | Y | `1` 이상의 정수, 별도 업무 상한 없음 |

### 최초 성공 응답

`201 Created`

```json
{
  "pointTransactionId": 501,
  "userId": 10,
  "chargedAmount": 10000,
  "balance": 15000,
  "chargedAt": "2026-07-10T04:30:00.123Z"
}
```

같은 요청을 재생할 때 사용자의 현재 잔액이 이후 변경되었더라도 `balance`에는 최초 충전 직후의 `15000`을 반환한다.

### 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 헤더가 없음 |
| 400 | `INVALID_IDEMPOTENCY_KEY` | 헤더 형식이나 길이가 잘못됨 |
| 400 | `VALIDATION_ERROR` | 잘못된 사용자 ID, 누락·0·음수·소수 충전액 |
| 404 | `USER_NOT_FOUND` | 사용자가 존재하지 않음 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 범위의 키를 다른 요청에 재사용 |
| 422 | `POINT_BALANCE_OVERFLOW` | 충전 후 잔액이 signed `BIGINT` 범위를 넘음 |
| 503 | `CONCURRENCY_TIMEOUT` | 지갑 또는 멱등 키의 DB 락 대기 시간 초과 |

## 3. 주문·결제

메뉴 1개를 수량 1개로 주문하고 포인트 결제를 원자적으로 완료한다. 클라이언트가 가격을 보내지 않으며 서버가 현재 메뉴 가격을 사용한다.

```http
POST /api/v1/orders
Idempotency-Key: 3ce895c5-e0d1-4cbc-a586-3ae206a637b8
Content-Type: application/json
```

### 요청 본문

```json
{
  "userId": 10,
  "menuId": 2
}
```

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `userId` | positive `long` | Y | 사전 등록된 사용자 ID |
| `menuId` | positive `long` | Y | 사전 등록된 메뉴 ID |

### 최초 성공 응답

`201 Created`

```json
{
  "orderId": 1001,
  "userId": 10,
  "menu": {
    "menuId": 2,
    "name": "카페라떼"
  },
  "unitPrice": 5000,
  "quantity": 1,
  "paidAmount": 5000,
  "remainingPointBalance": 10000,
  "status": "PAID",
  "paidAt": "2026-07-10T04:35:00.456Z"
}
```

이 응답은 주문, 포인트 차감 원장, Outbox 이벤트가 MySQL에 모두 커밋되었음을 뜻한다. 데이터 플랫폼이 이미 수신했다는 뜻은 아니다. 외부 전송 실패는 이 응답을 실패로 바꾸지 않는다.

### 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 헤더가 없음 |
| 400 | `INVALID_IDEMPOTENCY_KEY` | 헤더 형식이나 길이가 잘못됨 |
| 400 | `VALIDATION_ERROR` | 사용자 또는 메뉴 ID가 누락되거나 양수가 아님 |
| 404 | `USER_NOT_FOUND` | 사용자가 존재하지 않음 |
| 404 | `MENU_NOT_FOUND` | 메뉴가 존재하지 않음 |
| 409 | `MENU_NOT_ORDERABLE` | 메뉴가 `INACTIVE` 상태임 |
| 409 | `INSUFFICIENT_POINTS` | 결제 가능한 포인트가 부족함 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 범위의 키를 다른 주문에 재사용 |
| 503 | `CONCURRENCY_TIMEOUT` | 지갑 또는 멱등 키의 DB 락 대기 시간 초과 |

잔액 부족, 비활성 메뉴에서는 주문·포인트 원장·Outbox가 남지 않고 최초 오류 응답만 멱등 결과로 커밋된다. 저장 실패와 일시적 서버 오류에서는 멱등 결과도 롤백된다.

## 4. 인기 메뉴 조회

조회 시각 기준 직전 168시간의 인기 활성 메뉴를 최대 3개 반환한다.

```http
GET /api/v1/menus/popular
```

### 집계 계약

- `to`: 요청 처리 중 UTC `Clock`에서 한 번 캡처한 시각
- `from`: `to - 168시간`
- 조건: `status = PAID`, `paid_at >= from`, `paid_at < to`, 현재 메뉴 `ACTIVE`
- 순서: `orderCount DESC`, `menuId ASC`
- 정본: MySQL 주문 원본
- 최대 항목 수: 3

### 성공 응답

`200 OK`

```json
{
  "from": "2026-07-03T04:40:00.000Z",
  "to": "2026-07-10T04:40:00.000Z",
  "items": [
    {
      "rank": 1,
      "menuId": 2,
      "name": "카페라떼",
      "price": 5000,
      "orderCount": 42
    },
    {
      "rank": 2,
      "menuId": 1,
      "name": "아메리카노",
      "price": 4500,
      "orderCount": 31
    }
  ]
}
```

응답의 `name`과 `price`는 현재 활성 메뉴 정보다. `orderCount`는 기간 내 결제 주문 원본의 정확한 횟수다. 대상 메뉴가 없으면 같은 `from`, `to`와 빈 `items`를 반환한다. 이 API는 Redis 인기 카운터나 Kafka 소비 결과를 사용하지 않는다.

## 5. 데이터 플랫폼 이벤트 계약

공개 클라이언트 API가 아니라 Outbox 발행 어댑터가 사용하는 외부 계약이다.

### Phase 1 — Mock HTTP

```http
POST {DATA_PLATFORM_BASE_URL}/api/v1/order-events
X-Event-Id: 7e8422d3-9638-4e40-a230-efbea89d8d4a
Content-Type: application/json
```

```json
{
  "schemaVersion": 1,
  "eventId": "7e8422d3-9638-4e40-a230-efbea89d8d4a",
  "eventType": "ORDER_PAID",
  "occurredAt": "2026-07-10T04:35:00.456Z",
  "orderId": 1001,
  "userId": 10,
  "menuId": 2,
  "paymentAmount": 5000
}
```

`occurredAt`은 주문의 `paidAt`과 같은 UTC 시각이며 주문 트랜잭션에서 고정된다.

- 모든 `2xx` 응답은 수신 성공으로 간주한다.
- timeout, 네트워크 오류, `429`, `5xx`는 재시도한다.
- 그 밖의 `4xx`는 계약·인증과 같은 영구 오류로 보고 즉시 `FAILED`로 격리한다.
- 정상 상태의 첫 전송 시도 목표는 주문 커밋 후 1초 이내다.
- 한 번의 자동 처리 주기에서 최초 dispatch 1회와 최대 10회의 재선점을 합쳐 최대 11회이며, 정상 경로의 실제 외부 호출도 최대 11회다. 수동 재처리는 새로운 주기다.
- 수신자는 동일 `eventId`의 중복 요청을 한 번만 반영해야 한다.

### Phase 2 — Kafka

- 토픽: `coffee.order.paid.v1`
- 메시지 키: `orderId` 문자열
- 메시지 값: Phase 1과 같은 논리 JSON 이벤트
- 성공 기준: Kafka broker의 성공 ack를 받은 뒤 Outbox를 `PUBLISHED`로 변경

이 토픽은 데이터 플랫폼의 공식 ingestion 경계다. broker ack 이후 소비·재시도·DLQ·업무 반영과 `eventId` 중복 제거는 데이터 플랫폼이 책임지며, 주문 서비스는 downstream 소비 완료까지 추적하지 않는다.

파티션 수, 보존 기간, 소비자 그룹과 스키마 레지스트리 사용 여부는 Kafka 도입 직전에 부하·운영 조건을 확인해 별도 ADR로 결정한다.

## 6. 오류 코드 목록

아래 HTTP 상태, `code`, 기본 `message`는 공통 오류 카탈로그와 동일하게 유지한다. `fieldErrors`는 구조적 입력 검증에서만 선택적으로 추가한다.

| HTTP | 코드 | 기본 `message` | 의미 | 재시도 지침 |
| --- | --- | --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 요청 값이 올바르지 않습니다. | 요청 형식 또는 필드 제약 위반 | 요청 수정 후 같은 키 또는 새 키 사용 가능 |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | Idempotency-Key 헤더가 필요합니다. | 멱등 키 누락 | 같은 요청에 키를 추가 |
| 400 | `INVALID_IDEMPOTENCY_KEY` | Idempotency-Key 형식이 올바르지 않습니다. | 멱등 키 형식 위반 | 키 수정 |
| 404 | `USER_NOT_FOUND` | 사용자를 찾을 수 없습니다. | 사용자 없음 | 사용자 식별값 확인 |
| 404 | `MENU_NOT_FOUND` | 메뉴를 찾을 수 없습니다. | 메뉴 없음 | 메뉴 목록 확인 후 새 키 사용 |
| 409 | `MENU_NOT_ORDERABLE` | 주문할 수 없는 메뉴입니다. | 비활성 메뉴 | 다른 메뉴와 새 키 사용 |
| 409 | `INSUFFICIENT_POINTS` | 포인트 잔액이 부족합니다. | 잔액 부족 | 충전 후 새 주문 키로 재시도 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 동일한 멱등 키가 다른 요청에 사용되었습니다. | 같은 키와 다른 요청 해시 | 새 키 사용 |
| 422 | `POINT_BALANCE_OVERFLOW` | 포인트 잔액이 범위를 초과합니다. | 정수 범위 초과 | 충전액과 키 변경 |
| 503 | `CONCURRENCY_TIMEOUT` | 동시 요청 처리 대기 시간이 초과되었습니다. | DB 락 대기 시간 초과 | 같은 키로 짧은 backoff 후 재시도 |
| 503 | `DATABASE_UNAVAILABLE` | 데이터베이스를 일시적으로 사용할 수 없습니다. | MySQL 연결·가용성 문제 | 같은 키로 재시도 |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 오류가 발생했습니다. | 분류되지 않은 서버 오류 | 같은 키로 재시도 전 운영 확인 |

`503` 응답에는 가능한 경우 `Retry-After: 1`을 포함한다. 주문 응답에는 데이터 플랫폼이나 Redis 장애 코드를 노출하지 않는다. Redis는 DB로 폴백하고 외부 이벤트는 Outbox가 재시도하기 때문이다.

## 7. 계약 테스트 체크리스트

### 공통 오류와 멱등성

- 같은 키·같은 성공 요청은 최초 HTTP 상태와 JSON 본문을 그대로 재생하는가?
- 결정적 오류 재생은 최초 `code`, `message`, 선택적 `fieldErrors`를 유지하면서 `timestamp`와 `traceId`를 현재 요청 값으로 새로 만드는가?
- 최초 오류와 재생 오류 모두 응답 `traceId`가 해당 요청 로그의 식별자와 일치하는가?

### 메뉴

- 메뉴가 ID 오름차순이고 `ACTIVE`만 포함되는가?
- 빈 목록이 `200`과 빈 배열인가?
- Redis 장애가 Phase 2에서 API 오류로 노출되지 않는가?

### 충전

- 양수 정수만 허용하는가?
- 같은 키·같은 성공 요청의 HTTP 상태와 응답 본문이 최초 결과와 정확히 같은가?
- 최초 응답 후 잔액이 바뀌어도 재생 응답의 `balance`가 바뀌지 않는가?
- 같은 키·다른 금액이 `409`인가?
- 오버플로 응답의 안정적인 비즈니스 오류 payload가 저장되어 같은 키에서 재생되고 요청 메타데이터는 새로 생성되는가?

### 주문

- 클라이언트 가격 필드가 거절되는가?
- 잔액 부족과 비활성 메뉴에서 어떤 쓰기도 남지 않는가?
- 잔액 부족과 비활성 메뉴의 도메인 쓰기는 없고 안정적인 오류 멱등 결과만 남으며, 재생 시 요청 메타데이터는 현재 값인가?
- 같은 키 동시 요청에서 주문·결제·Outbox 효과가 한 번뿐인가?
- 외부 플랫폼 실패와 무관하게 커밋된 주문은 `201`인가?

### 인기 메뉴

- 정확히 `from`인 주문은 포함하고 정확히 `to`인 주문은 제외하는가?
- `PAID`가 아닌 주문과 현재 비활성 메뉴를 제외하는가?
- 동률에서 메뉴 ID 오름차순인가?
- 현재 메뉴 정보와 주문 원본 횟수를 함께 반환하는가?
