# AGENTS.md

## 역할과 적용 범위

- 이 파일은 저장소 전체에 적용되는 루트 작업 라우터다. 상세 계약과 구현 규칙을 복제하지 않고 아래 정본 문서로 연결한다.
- 작업을 시작하기 전에 `README.md`, `build.gradle`, 실제 `src/`를 확인해 현재 구현 단계를 판단한다.
- 하위 디렉터리에 별도 `AGENTS.md`가 생기면 해당 범위에서는 그 지침을 함께 따른다.

## 정본 라우팅

| 작업 유형 | 먼저 읽을 정본 |
| --- | --- |
| 현재 구현 상태, 실행 방법, 기술 개요 | `README.md`, `build.gradle` |
| 제품 목표, 범위, 비즈니스 규칙, 수용 기준 | `docs/PRD.md` |
| 모듈 경계, 트랜잭션, 동시성, Outbox, 테스트 전략 | `docs/ARCHITECTURE.md` |
| 패키지, 계층 책임, DB 마이그레이션, 시간·설정·로그, 구현·테스트·커밋 규칙 | `docs/CONVENTIONS.md` |
| HTTP·이벤트 요청/응답과 오류 계약 | `docs/API.md` |
| 테이블, 제약, 인덱스, 상태 전이 | `docs/ERD.md` |
| 기술 선택 이유와 변경이 필요한 결정 | `docs/adr/README.md`와 연결된 ADR |

- 문서가 충돌하면 더 구체적인 정본과 최신 ADR을 따르고, 구현과 문서를 같은 변경에서 맞춘다.

## 저장소 지도

- `src/main/java/com/coffeeorder/` — 애플리케이션 코드와 진입점
- `src/main/resources/application.yml` — Spring Boot 런타임 설정
- `src/test/java/com/coffeeorder/` — 테스트 코드 루트. 실제 테스트 종류와 범위는 파일과 `build.gradle`에서 확인한다.
- `docs/` — 제품, 아키텍처, 컨벤션, API, 데이터 계약의 정본
- `build.gradle` — 실제 적용된 런타임, 플러그인, 의존성의 기준
- `gradlew`, `gradlew.bat`, `gradle/wrapper/`, `settings.gradle` — Gradle Wrapper와 프로젝트 설정
- `.gradle/`, `build/`, `out/`, `.idea/` — 생성물 또는 로컬 도구 상태이므로 직접 편집하지 않는다.

## 핵심 작업 규칙

- 문서의 `설계 확정`을 구현 완료로 간주하지 않는다. 의존성과 코드 경로가 실제로 존재하는지 먼저 확인한다.
- 기본 구현 순서는 `docs/ARCHITECTURE.md`의 구현 순서를 따른다. 별도 요구가 없다면 Phase 1보다 Kafka·Redis 중심의 Phase 2를 앞세우지 않는다.
- 코드 작성 시 `docs/CONVENTIONS.md`의 기능별 패키지, 의존 방향, 네이밍, 계층 책임을 따른다.
- MySQL을 핵심 상태의 정본으로 유지한다. 로컬 락, 프로세스 메모리 또는 Redis에 포인트·주문의 정합성을 맡기지 않는다.
- 포인트 쓰기는 문서에 정의된 지갑 락 순서를 유지하고, 외부 네트워크 호출은 DB 락과 주문 트랜잭션 밖에서 수행한다.
- 제품 범위, API, 스키마 또는 아키텍처 계약을 변경하면 구현·테스트와 해당 정본 문서를 함께 갱신한다.
- 채택된 결정을 바꿀 때는 기존 ADR을 덮어쓰지 않고 새 ADR로 대체한다.
- 커밋, 원격 push, PR 생성은 사용자가 명시적으로 요청한 경우에만 수행한다.

## 실행과 검증

| 명령 | 용도 |
| --- | --- |
| `.\scripts\install-git-hooks.ps1` | clone별 공유 pre-commit hook 활성화 |
| `.\gradlew.bat spotlessCheck` | 공백·줄바꿈·Java 포맷 검증 |
| `.\gradlew.bat spotlessApply` | 포맷 위반 자동 수정 |
| `.\gradlew.bat bootRun` | 로컬 애플리케이션 실행 |
| `Invoke-RestMethod http://localhost:8080/actuator/health` | 실행 상태가 `UP`인지 확인 |
| `.\gradlew.bat test` | 개발 중 빠른 테스트 |
| `.\gradlew.bat clean build` | 작업 완료 전 전체 검증 |

- JDK 21과 저장소의 Gradle Wrapper를 사용한다.
- DB 락·제약·트랜잭션 동작은 H2로 대체하지 않고 MySQL Testcontainers로 검증한다.
- 포맷 오류는 `spotlessApply`로 수정한 뒤 diff를 확인하고 `spotlessCheck`를 다시 실행한다.
- 실행 경로나 설정을 변경했다면 테스트뿐 아니라 `bootRun`과 Health endpoint도 확인한다.
- 현재 설정에 없는 린트, 포맷, Docker, CI 명령을 추측해서 사용하지 않는다.
- 검증을 실행하지 못했다면 완료를 주장하지 말고 실행하지 못한 항목과 이유를 보고한다.
