# Phase 1 구현 계획

> 이 문서는 정본 계약을 PR 단위로 풀어낸 실행 계획이다. 제품·API·데이터·아키텍처 계약이 충돌하면 해당 정본과 최신 ADR을 우선하며, 진행 상태는 GitHub Issues에서 관리한다.

- 기준일: 2026-07-10
- 기준 구현: Spring Boot 애플리케이션, Actuator, Web, Validation만 존재하는 초기 골격
- 최종 목표: [PRD](../../PRD.md)의 **Phase 1 완료 정의** 충족

## 1. 최종 목표

Phase 1이 끝나면 다음 상태여야 한다.

- 활성 메뉴 목록, 포인트 충전, 주문·결제, 인기 메뉴의 네 API가 [API](../../API.md) 계약대로 동작한다.
- MySQL이 메뉴, 포인트, 주문, 멱등성, Outbox의 유일한 정본이다.
- 충전과 주문은 지갑 행 비관적 락과 DB 기반 멱등성으로 다중 인스턴스에서도 같은 보장을 제공한다.
- 주문, 포인트 차감 원장, Outbox, 멱등 완료 응답은 한 트랜잭션에서 커밋되거나 모두 롤백된다.
- Outbox는 Mock HTTP 데이터 플랫폼으로 중복 가능한 at-least-once 전송을 수행하고 재시도·lease 회수·실패 격리를 지원한다.
- MySQL Testcontainers 기반 단위·통합·동시성·외부 연동 실패 테스트가 통과한다.
- 구현, README와 `docs/` 정본이 서로 일치한다.

Kafka와 Redis는 Phase 2 범위이므로 이 계획에 포함하지 않는다.

## 2. 실행 원칙

- Phase 실행은 이 계획, Phase Step Issue 템플릿, 실행 스킬과 CI가 integration branch인 `develop`에 반영된 뒤 시작한다. 로컬에만 있는 운영 기반을 기준으로 Step branch를 만들지 않는다.
- **한 step은 한 branch와 한 PR**로 구현한다.
- 각 PR은 바로 이전 step이 `develop`에 병합된 상태에서 시작하고 `develop`을 base로 사용한다.
- Step branch를 만들기 전에 `develop`이 최신 `main`을 포함하는지, 작업 트리가 깨끗한지 확인한다.
- Step PR 자동 병합은 `develop`의 strict status check 또는 동등한 merge queue가 최신 base에서 `verify`를 강제할 때만 허용한다.
- 각 step의 진행 상태, 담당자, blocker, 실제 branch·PR과 검증 결과는 GitHub Issues에서 관리한다.
- 아직 공개 계약을 완성하지 못한 기반 PR은 외부 API를 노출하지 않고 내부 기능과 테스트만 추가한다.
- 각 PR은 코드와 해당 테스트를 함께 포함하며, 다음 step의 미완성 코드를 미리 넣지 않는다.
- API·스키마·아키텍처 계약을 변경해야 한다면 같은 PR에서 정본 문서와 ADR을 갱신한다.
- 커밋·원격 push·PR 생성 권한은 루트 `AGENTS.md`의 핵심 작업 규칙을 따른다.
- 모든 Step이 `develop`에 병합된 뒤 전체 완료 게이트를 다시 검증하고, `develop -> main` release PR은 사람이 최종 리뷰한다. Phase 자동화는 이 release PR을 병합하지 않는다.

## 3. PR 지도

| Step | PR 목표 | 완료 후 보장 |
| --- | --- | --- |
| [01](step-01-persistence-foundation.md) | MySQL·JPA·Flyway·Testcontainers 기반 | 빈 DB에서 전체 스키마와 seed를 재현한다. |
| [02](step-02-menu-api.md) | 공통 HTTP 오류 계약과 메뉴 API | 활성 메뉴를 ID 순으로 조회한다. |
| [03](step-03-point-domain-locking.md) | 포인트 지갑·원장과 비관적 락 | 내부 포인트 쓰기가 실제 MySQL 락으로 직렬화된다. |
| [04](step-04-idempotency-core.md) | DB 멱등성 실행기 | 같은 키 재생과 다른 요청 충돌을 트랜잭션 경계에서 구분한다. |
| [05](step-05-point-charge-api.md) | 멱등 포인트 충전 API | 충전 효과와 원장이 중복 없이 한 번만 기록된다. |
| [06](step-06-order-write-model.md) | 주문·결제 원장·Outbox 쓰기 모델 | 주문 트랜잭션을 구성할 영속 모델과 서비스가 준비된다. |
| [07](step-07-order-payment-api.md) | 주문·결제 API와 원자적 Facade | 결제 성공과 결정적 실패가 API·멱등 계약대로 커밋된다. |
| [08](step-08-outbox-delivery-core.md) | Outbox 선점·lease·재시도 상태 머신 | 다중 작업자가 이벤트를 안전하게 선점하고 결과를 기록한다. |
| [09](step-09-mock-http-publisher.md) | 비동기 Mock HTTP 발행 | 외부 호출을 트랜잭션 밖에서 수행하고 실패를 재시도·격리한다. |
| [10](step-10-popular-menu-api.md) | 최근 168시간 인기 메뉴 API | MySQL 주문 원본에서 정확한 상위 3개를 계산한다. |
| [11](step-11-observability-hardening.md) | 관측성과 런타임 오류 하드닝 | 락·멱등·Outbox·집계 상태를 운영 신호로 확인한다. |
| [12](step-12-phase1-acceptance.md) | 동시성 수용 테스트와 문서 최종 게이트 | Phase 1의 전체 완료 정의를 증명한다. |

## 4. 공통 PR 완료 게이트

모든 step은 [명령 정본](../../COMMANDS.md)에서 현재 셸에 맞는 표기를 사용해 `spotlessCheck`, `test`, `clean build`를 통과해야 한다.

추가 규칙은 다음과 같다.

- DB 동작은 H2나 Repository mock이 아니라 MySQL Testcontainers로 검증한다.
- 동시성·비동기 테스트의 작성 규칙은 [Conventions의 테스트 섹션](../../CONVENTIONS.md#테스트)을 따른다.
- 설정 또는 실행 경로를 바꾼 PR은 실제 MySQL과 함께 `bootRun` 및 `/actuator/health`의 `UP`을 확인한다.
- 실행한 검증과 결과, 검증하지 못한 항목의 이유는 해당 Step Issue에 기록하며 미검증 Step은 완료 처리하지 않는다.

## 5. 공통 금지사항

- 로컬 JVM 락이나 프로세스 메모리로 포인트·주문 정합성을 보장하지 않는다.
- 외부 HTTP 호출을 지갑 락 또는 주문 DB 트랜잭션 안에서 수행하지 않는다.
- JPA 자동 DDL로 Flyway migration을 대체하지 않는다.
- Entity를 HTTP 응답으로 직접 노출하거나 Facade에서 Repository를 직접 호출하지 않는다.
- Outbox 전달을 exactly-once라고 표현하지 않는다.
- Phase 2의 Kafka·Redis, 관리자 API, 취소·환불을 선행 구현하지 않는다.
