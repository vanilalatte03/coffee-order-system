# Step 10. 최근 168시간 인기 메뉴 API

- PR 제목 예시: `feat: add popular menu ranking api`
- branch 예시: `feature/phase1-step-10-ranking`
- 선행 step: Step 09

## 목표

`GET /api/v1/menus/popular`를 MySQL의 결제 주문 원본에서 정확히 집계해 Phase 1의 네 번째 API를 완성한다.

## 구현 범위

- 주입한 UTC `Clock`에서 요청별 `Instant`를 한 번 캡처한 뒤 MySQL `DATETIME(6)` 정밀도에 맞게 microsecond로 truncate한 값을 `to`로 사용하고 `from = to - 168시간`을 계산한다.
- `PAID`, `[from, to)`, 현재 메뉴 `ACTIVE` 조건을 적용한 집계 query를 구현한다.
- 집계 query는 ranking 기능이 소유한 `domain/ranking/repository/PopularMenuQueryRepository`에 두고 `RankingService`가 주문 기능의 `OrderRepository`를 직접 참조하지 않게 한다.
- `orderCount DESC`, `menuId ASC`, 최대 3개 순서를 DB query와 결과 매핑에서 유지한다.
- 현재 메뉴의 이름·가격과 주문 원본 count를 반환한다.
- rank, from, to, 빈 items를 `docs/API.md`와 같은 DTO로 반환한다.
- query 의도와 인덱스 사용을 통합 테스트에서 확인한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/ranking/`
- `src/main/java/com/coffeeorder/domain/ranking/repository/PopularMenuQueryRepository.java`
- `src/test/java/com/coffeeorder/domain/ranking/`

## 테스트

- 정확히 `from`인 주문은 포함하고 정확히 `to`인 주문은 제외한다.
- `PAID`가 아닌 데이터와 현재 `INACTIVE` 메뉴는 제외한다.
- 최근 168시간 밖의 주문은 제외한다.
- count 내림차순과 menu ID 오름차순 동률 규칙이 적용된다.
- 메뉴가 네 개 이상이어도 세 개만 반환한다.
- 대상이 없으면 같은 from/to와 빈 items를 반환한다.
- 응답은 현재 메뉴 이름·가격을 사용하고 count는 주문 snapshot 원본에서 계산한다.
- 고정 `Clock`으로 시간 경계가 재현 가능하다.
- 나노초가 포함된 fixed `Clock`에서도 `to`가 microsecond로 truncate되고, 그 정밀도를 기준으로 정확히 `from`인 주문은 포함하며 정확히 `to`인 주문은 제외한다.

## 수용 기준

- [ ] Redis·Kafka 파생 데이터가 아닌 MySQL 주문 원본을 정본으로 사용한다.
- [ ] 요청 하나에서 캡처한 시각을 전체 계산에 재사용한다.
- [ ] API와 query가 동일한 microsecond 정밀도의 `from`·`to`를 사용한다.
- [ ] 조회 Service가 read-only transaction을 사용한다.
- [ ] `RankingService`는 ranking 소유 query repository만 사용하고 `OrderRepository`를 직접 참조하지 않는다.
- [ ] `idx_orders_popular`를 지원하는 query 모양을 유지한다.
- [ ] API response가 RFC 3339 UTC 형식과 rank 계약을 만족한다.

## 제외 범위

- 일별 집계 테이블
- Redis 인기 순위
- pagination과 기간 query parameter
- 비활성 메뉴 포함 옵션
