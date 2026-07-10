# ADR (Architecture Decision Record)

되돌리기 어렵거나 “왜 이렇게 설계했는가?”라는 질문에 답해야 하는 결정을 기록한다. 결정 1건을 파일 1개로 관리하며, 이미 채택된 결정을 바꿀 때는 기존 문서를 고쳐 쓰지 않고 새로운 ADR로 대체한다.

## 언제 작성하는가

- 대안이 여러 개이고 선택에 명확한 트레이드오프가 있을 때
- 다중 인스턴스, 정합성, 장애 처리처럼 나중에 변경 비용이 큰 결정일 때
- 코드만 보고 선택 이유를 복원하기 어려울 때

## 정본 문서

ADR은 선택 이유를 기록하며 상세 계약을 복제하지 않는다.

| 내용 | 정본 |
| --- | --- |
| 제품 목표·범위·비즈니스 규칙 | [`docs/PRD.md`](../PRD.md) |
| 시스템 구조·트랜잭션·장애 처리 | [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md) |
| 테이블·컬럼·제약·인덱스 | [`docs/ERD.md`](../ERD.md) |
| HTTP·이벤트 계약과 오류 코드 | [`docs/API.md`](../API.md) |

사소하거나 쉽게 되돌릴 수 있는 구현 세부사항은 ADR로 만들지 않는다.

## 상태값

| 상태 | 의미 |
| --- | --- |
| 제안됨 | 논의 중이며 아직 확정되지 않음 |
| 채택됨 | 결정이 확정되었으며 구현은 이 결정을 따라야 함 |
| 폐기됨 | 더 이상 유효하지 않음 |
| 대체됨 | 새로운 ADR이 이 결정을 대체함. 대체 ADR 번호를 함께 기록 |

ADR 상태는 **의사결정 상태**이며 구현 완료 여부를 뜻하지 않는다. 현재 작업공간은 설계 단계이고, `적용 단계`가 Phase 2인 결정은 핵심 기능 완료 후 구현한다.

## 파일명

`NNN-제목-kebab.md` 형식을 사용한다. 번호는 3자리 일련번호이며 재사용하지 않는다.

## 현재 ADR

| ADR | 상태 | 적용 단계 | 결정 요약 |
| --- | --- | --- | --- |
| [ADR-001](./001-modular-monolith-shared-mysql.md) | 채택됨 | Phase 1 | 모듈러 모놀리스와 공유 MySQL을 정합성 기준으로 사용 |
| [ADR-002](./002-pessimistic-point-lock.md) | 채택됨 | Phase 1 | 사용자 포인트 지갑 행에 비관적 락 적용 |
| [ADR-003](./003-idempotent-write-api.md) | 채택됨 | Phase 1 | 충전·주문에 DB 기반 `Idempotency-Key` 적용 |
| [ADR-004](./004-transactional-outbox.md) | 채택됨 | Phase 1·2 | 주문 이벤트를 Transactional Outbox로 전달 |
| [ADR-005](./005-database-popular-ranking.md) | 채택됨 | Phase 1 | 인기 메뉴를 MySQL `PAID` 주문 원본에서 집계 |
| [ADR-006](./006-redis-menu-cache.md) | 채택됨 | Phase 2 | Redis를 활성 메뉴 목록 캐시로만 사용 |
| [ADR-007](./007-mysql-testcontainers.md) | 채택됨 | Phase 1 | 실제 MySQL Testcontainers로 DB 특성 검증 |

Kafka는 ADR-004의 후속 발행 어댑터로 도입한다. 토픽 파티션, 보존 기간, 스키마 레지스트리처럼 아직 부하·운영 조건이 없는 선택은 추측하지 않으며 실제 도입 직전에 별도 ADR로 기록한다.

## 새 ADR 작성

[`000-template.md`](./000-template.md)를 복사해 다음 번호를 부여한다. 관련 정본 문서와 구현·테스트가 함께 바뀌는지 확인한다.
