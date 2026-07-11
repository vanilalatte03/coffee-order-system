# 구현 Phase 계획

이 디렉터리는 제품·API·데이터·아키텍처 정본을 PR 단위로 실행할 수 있게 나눈 계획을 관리한다. Phase 문서는 정본 계약을 대체하지 않는다.

## 정본과 상태 관리

- 제품 목표와 수용 기준은 [`docs/PRD.md`](../PRD.md)를 따른다.
- 시스템 구조와 구현 순서는 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md)를 따른다.
- HTTP·이벤트 계약은 [`docs/API.md`](../API.md), 데이터 계약은 [`docs/ERD.md`](../ERD.md)를 따른다.
- 기술 선택과 변경 이유는 [`docs/adr/`](../adr/README.md)의 최신 ADR을 따른다.
- Phase 문서와 정본이 충돌하면 더 구체적인 정본과 최신 ADR을 우선한다.
- Step의 진행 상태, 담당자, blocker, 실제 branch·PR과 검증 결과는 GitHub Issues를 정본으로 관리한다.
- Step의 범위, 수용 기준 또는 검증 방법이 바뀌면 구현 PR에서 이 계획과 관련 정본을 함께 갱신한다.
- Phase 완료 후 해당 계획은 완료된 실행 기록으로 동결하고, 현재 계약은 정본 문서와 최신 ADR에서 관리한다.

## 브랜치 모델

- 각 Step branch와 PR은 integration branch인 `develop`을 대상으로 한다.
- 다음 Step은 직전 Step PR이 `develop`에 병합된 뒤 최신 `develop`에서 시작한다.
- Phase의 모든 Step이 끝나면 `develop`에서 전체 완료 게이트를 다시 검증한다.
- `develop`에서 `main`으로 가는 release PR은 사람이 최종 리뷰하며 자동화가 병합하지 않는다.
- Phase 시작 전 `develop`은 최신 `main`과 Phase 운영 기반을 포함해야 한다. 그렇지 않으면 Step 구현을 시작하지 않는다.
- Step PR을 자동 병합하려면 `develop`의 server-side 정책이 최신 base와 동기화되지 않은 head를 거부하고 `verify`를 다시 요구해야 한다. 이 조건을 확인할 수 없으면 자동화는 merge-ready 상태에서 멈춘다.

## Phase 목록

| Phase | 계획 | 목표 |
| --- | --- | --- |
| Phase 1 | [핵심 기능과 정합성](./phase-1/README.md) | MySQL 기반 메뉴·포인트·주문·Outbox와 핵심 수용 테스트 완성 |
