# ADR-007: MySQL Testcontainers로 DB 통합·동시성 검증

- 상태: 채택됨
- 날짜: 2026-07-10
- 관련: [`PRD` 품질 목표](../PRD.md#22-품질-목표), [`ARCHITECTURE` 테스트 전략](../ARCHITECTURE.md#12-테스트-전략), [`ERD`](../ERD.md)

## 맥락

핵심 보장은 MySQL의 `SELECT ... FOR UPDATE`, 유니크 제약, `SKIP LOCKED`, 격리 수준, 인덱스와 트랜잭션 롤백에 의존한다. H2나 Repository mock은 이런 동작을 MySQL과 동일하게 재현하지 못할 수 있다. 반면 개발자가 공유 테스트 DB를 직접 관리하면 테스트가 서로 간섭하고 초기 상태 재현이 어렵다. 사용 가능한 개발 환경에는 Docker가 있다.

## 검토한 대안

- **단위 테스트와 mock만 사용** — 빠르지만 SQL, 락, Flyway, 실제 트랜잭션 경쟁을 검증하지 못한다.
- **H2 인메모리 DB** — 시작이 빠르지만 MySQL 문법·격리·락과 `SKIP LOCKED` 차이로 잘못된 성공 신호를 줄 수 있다.
- **공유 개발 MySQL** — 실제 DB를 사용하지만 테스트 데이터 충돌, 수동 설정, 버전 드리프트가 발생한다.
- **Testcontainers MySQL** — 테스트 실행 시 고정 버전 MySQL 컨테이너를 만들고 종료 시 제거한다. 실제 동작을 재현하지만 Docker와 시작 시간이 필요하다.

## 결정

DB 동작에 의존하는 통합·동시성 테스트는 Testcontainers로 고정된 MySQL 8.x 이미지를 실행한다. Flyway를 실제로 적용한 뒤 테스트한다. 순수 도메인 규칙은 컨테이너 없는 빠른 단위 테스트로 유지한다.

동시성 테스트는 다음 조건을 지킨다.

- 각 작업이 서로 다른 DB connection과 트랜잭션을 사용한다.
- 시작 장벽으로 요청이 실제로 경쟁하게 만든다.
- 테스트 메서드 전체를 하나의 `@Transactional`로 감싸지 않는다.
- 락 대기와 비동기 결과는 무한 sleep이 아니라 제한 시간과 Awaitility로 검증한다.
- 성공 건수뿐 아니라 최종 잔액, 원장, 주문, Outbox 개수까지 함께 검증한다.

## 결과

- 운영과 같은 MySQL 락·제약·Flyway 동작을 로컬과 CI에서 재현할 수 있다.
- 개발자 PC에 별도 MySQL 설치와 테스트 스키마 관리가 필요 없다.
- Docker가 필수이며 첫 컨테이너 시작으로 테스트가 느려진다.
- 빠른 단위 테스트와 느린 통합 테스트를 태그·Gradle task로 분리한다.
- CI에서는 Docker 실행 가능 여부와 컨테이너 로그 수집을 구성해야 한다.
- Phase 2의 Kafka와 Redis도 필요할 때 Testcontainers로 같은 방식의 어댑터 통합 테스트를 추가한다.
