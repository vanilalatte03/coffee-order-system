# Step 02. 공통 HTTP 계약과 활성 메뉴 API

- PR 제목 예시: `feat: add active menu query api`
- branch 예시: `feature/phase1-step-02-menu-api`
- 선행 step: Step 01

## 목표

공통 오류 응답 기반을 만들고 `GET /api/v1/menus`를 첫 번째 완결된 수직 기능으로 제공한다.

## 구현 범위

- `Menu`, `MenuStatus`, `MenuRepository`를 ERD와 기능별 패키지 규칙에 맞게 구현한다.
- `ACTIVE` 메뉴만 ID 오름차순으로 조회하는 read-only Service를 구현한다.
- Entity와 분리된 응답 DTO 및 `MenuController`를 구현한다.
- `docs/API.md` 형식의 공통 오류 응답, field error, 전역 예외 변환 기반을 추가한다.
- 알 수 없는 JSON 필드를 거절하도록 Jackson 입력 정책을 고정한다.
- 요청별 `traceId`를 생성·전파해 오류 응답과 로그가 같은 식별자를 사용하게 한다.
- production 쓰기 endpoint를 미리 추가하지 않고 test source 전용 fixture Controller와 Request DTO를 사용한 `GlobalExceptionHandler` slice 테스트로 field validation, malformed JSON과 알 수 없는 필드 변환을 검증한다.
- 메뉴가 없을 때 `200 OK`와 빈 `items`를 반환한다.

## 주요 변경 예상 경로

- `src/main/java/com/coffeeorder/domain/menu/`
- `src/main/java/com/coffeeorder/global/`
- `src/test/java/com/coffeeorder/domain/menu/`
- `src/test/java/com/coffeeorder/global/`

## 테스트

- 활성 메뉴만 반환한다.
- 결과를 메뉴 ID 오름차순으로 반환한다.
- 활성 메뉴가 없으면 빈 배열을 반환한다.
- Entity를 직렬화하지 않고 API 명세와 정확히 같은 필드만 반환한다.
- test source 전용 fixture Controller를 사용하는 `GlobalExceptionHandler` slice 테스트에서 field validation, malformed JSON과 알 수 없는 필드가 내부 예외 노출 없이 공통 오류 형식으로 변환된다.
- 실제 포인트 충전·주문 쓰기 API의 validation 통합 검증은 각각 Step 05와 Step 07에서 수행한다.
- 오류 응답의 `traceId`가 같은 요청 로그의 값과 일치한다.

## 수용 기준

- [ ] `GET /api/v1/menus`가 `200 OK` 계약을 만족한다.
- [ ] Controller는 Service만 호출하며 Repository를 직접 참조하지 않는다.
- [ ] 조회 Service가 `readOnly = true` 트랜잭션을 사용한다.
- [ ] 현재 메뉴 이름과 가격을 `long` 계약으로 반환한다.
- [ ] 오류 변환용 fixture Controller와 Request DTO는 test source에만 존재하며 production endpoint로 노출되지 않는다.
- [ ] Phase 2의 Redis 경계나 폴백 코드를 미리 추가하지 않는다.

## 제외 범위

- 메뉴 등록·수정·삭제 API
- Redis 메뉴 캐시
- 포인트 및 주문 기능
- 모든 업무 오류 코드의 선행 구현
