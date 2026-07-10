# AGENTS.md

## 역할과 적용 범위

- 이 파일은 저장소 전체에 적용되는 루트 작업 라우터다. 상세 계약과 구현 규칙을 복제하지 않고 아래 정본 문서와 실행 계획으로 연결한다.
- 작업을 시작하기 전에 `README.md`, `build.gradle`, 실제 `src/`를 확인해 현재 구현 단계를 판단한다.
- 하위 디렉터리에 별도 `AGENTS.md`가 생기면 해당 범위에서는 그 지침을 함께 따른다.

## 문서 라우팅

| 작업 유형 | 먼저 읽을 문서 |
| --- | --- |
| 현재 구현 상태와 기술 개요 | `README.md`, `build.gradle` |
| Git hook, 포맷, 실행, 테스트, 전체 검증 명령 | `docs/COMMANDS.md` |
| 제품 목표, 범위, 비즈니스 규칙, 수용 기준 | `docs/PRD.md` |
| 모듈 경계, 트랜잭션, 동시성, Outbox, 테스트 전략 | `docs/ARCHITECTURE.md` |
| Phase별 구현 순서, PR 분할, step 범위와 완료 게이트 | `docs/phases/README.md`와 연결된 Phase 계획 |
| 패키지, 계층 책임, DB 마이그레이션, 시간·설정·로그, 구현·테스트·커밋 규칙 | `docs/CONVENTIONS.md` |
| HTTP·이벤트 요청/응답과 오류 계약 | `docs/API.md` |
| 테이블, 제약, 인덱스, 상태 전이 | `docs/ERD.md` |
| 기술 선택 이유와 변경이 필요한 결정 | `docs/adr/README.md`와 연결된 ADR |

- Phase 계획은 정본 계약을 PR 단위로 풀어낸 실행 계획이다. 문서가 충돌하면 더 구체적인 정본과 최신 ADR을 따르고, 구현과 문서를 같은 변경에서 맞춘다.

## 저장소 지도

- `src/main/java/com/coffeeorder/` — 애플리케이션 코드와 진입점
- `src/main/resources/application.yml` — Spring Boot 런타임 설정
- `src/test/java/com/coffeeorder/` — 테스트 코드 루트. 실제 테스트 종류와 범위는 파일과 `build.gradle`에서 확인한다.
- `docs/` — 제품, 아키텍처, 컨벤션, API, 데이터 계약의 정본
- `docs/phases/` — 정본 계약을 Phase와 PR step 단위로 나눈 실행 계획. 진행 상태는 GitHub Issues에서 관리한다.
- `.agents/` — 코드 리뷰 등 저장소 로컬 에이전트 스킬
- `.githooks/` — 공유 pre-commit hook
- `scripts/` — Git hook 활성화 등 저장소 운영 스크립트
- `.github/` — Phase Step Issue 템플릿과 GitHub 협업 설정
- `build.gradle` — 실제 적용된 런타임, 플러그인, 의존성의 기준
- `gradlew`, `gradlew.bat`, `gradle/wrapper/`, `settings.gradle` — Gradle Wrapper와 프로젝트 설정
- `.gradle/`, `build/`, `out/`, `.idea/` — 생성물 또는 로컬 도구 상태이므로 직접 편집하지 않는다.

## 핵심 작업 규칙

- 문서의 `설계 확정`을 구현 완료로 간주하지 않는다. 의존성과 코드 경로가 실제로 존재하는지 먼저 확인한다.
- 기본 구현 순서는 `docs/ARCHITECTURE.md`의 구현 순서를 따른다. 별도 요구가 없다면 Phase 1보다 Kafka·Redis 중심의 Phase 2를 앞세우지 않는다.
- Phase 작업은 `docs/phases/README.md`와 해당 step 문서를 따르며, 한 step은 한 GitHub Issue·branch·PR로 실행한다.
- Phase 문서는 범위·수용 기준과 공통 완료 게이트를, Issue는 진행 상태·담당자·blocker·실제 branch·PR과 검증 결과를 관리하며 PR은 `Closes #이슈번호`로 연결한다.
- 코드 작성 시 `docs/CONVENTIONS.md`의 기능별 패키지, 의존 방향, 네이밍, 계층 책임을 따른다.
- MySQL을 핵심 상태의 정본으로 유지한다. 로컬 락, 프로세스 메모리 또는 Redis에 포인트·주문의 정합성을 맡기지 않는다.
- 포인트 쓰기는 문서에 정의된 지갑 락 순서를 유지하고, 외부 네트워크 호출은 DB 락과 주문 트랜잭션 밖에서 수행한다.
- 제품 범위, API, 스키마 또는 아키텍처 계약을 변경하면 구현·테스트와 해당 정본 문서를 함께 갱신한다.
- 채택된 결정을 바꿀 때는 기존 ADR을 덮어쓰지 않고 새 ADR로 대체한다.
- 커밋, 원격 push, PR 생성은 사용자의 명시 요청 또는 태스크의 명시된 완료 조건이 해당 산출물을 요구하는 경우에만 수행한다. Codex Cloud라는 이유만으로 권한을 추정하지 않는다.
- merge와 배포는 별도의 명시 요청 없이는 수행하지 않는다.
