---
name: review-code
description: "코드 리뷰 요청 시 사용 가능한 subagent/multi-agent 도구로 차원별 리뷰를 병렬 실행하고, 없으면 메인 에이전트가 차원별로 순차 리뷰한다. 오케스트레이터가 리뷰 범위를 1회 계산해 각 차원에 주입하고, 결과를 병합·우선순위화해 단일 read-only 리포트로 낸다. 트리거: '리뷰해줘', '코드 리뷰', '병렬 리뷰', '백엔드적으로 봐줘', '컨벤션 봐줘', '/review-code'."
---

## 역할

요청된 코드 또는 현재 브랜치 변경사항을, **차원별 전문 리뷰 패스**로 나누어 리뷰한다.
사용 중인 런타임이 subagent/multi-agent 도구를 제공하면 병렬 실행하고, 없으면 메인 에이전트가 같은 차원 잠금 규칙으로 순차 실행한다.
메인 대화가 오케스트레이터다. 코드 수정·리팩토링 패치·요청하지 않은 기능 추가는 하지 않는다(모든 차원 리뷰도 read-only).

## 왜 병렬로 쪼개는가

- **차원별 전문성**: 한 프롬프트가 여러 관점을 동시에 보면 주의가 분산된다. "너는 X만 본다"고 못박은 리뷰 패스는 그 렌즈로만 깊게 판다.
- **동시 실행 속도**: 런타임이 병렬 subagent/multi-agent 실행을 지원하면 여러 차원을 한 번에 던지고 결과만 모은다.

## 차원 (10개 구조, 프로젝트 기본 3개 활성)

| # | 차원 | 상태 | 초점 |
| --- | --- | --- | --- |
| 1 | correctness | **활성** | 논리 버그, 엣지 케이스, null/예외, 경계 조건 |
| 2 | security | 비활성 | 입력 검증, 인젝션, 비밀정보·내부 오류 노출, 안전하지 않은 외부 연동 |
| 3 | conventions | 비활성 | 레포 컨벤션 문서 위반, 네이밍, 시간 처리, 문서화 주석 |
| 4 | performance | 비활성 | N+1, 쿼리·인덱스, 락 보유 시간, Outbox 처리량, 불필요한 Phase 2 최적화 |
| 5 | test-coverage | **활성** | 누락 테스트, 취약한 단언, MySQL·동시성·롤백 검증 게이트 |
| 6 | architecture | **활성** | 기능별 패키지, 계층·트랜잭션 경계, 정본 저장소, 단계별 과한 설계 |
| 7 | cross-file-consistency | 비활성 | 구현·의존성·마이그레이션·API·ERD·ADR 간 계약 불일치 |
| 8 | privacy | 비활성 | 개인정보 처리, 로깅 노출, 보존 |
| 9 | cpu-perf-patterns | 비활성 | 핫 패스 할당, 불필요 반복, 동기 블로킹 |
| 10 | behavioral-correctness | 비활성 | PRD 수용 시나리오와 API 계약 대비 실제 동작, 회귀 |

차원 선택은 아래 기준으로 한다.

- 기본 리뷰는 `correctness`, `architecture`, `test-coverage` 3개만 활성화한다.
- 변경 파일이나 경로만 보고 다른 차원을 자동 추가하지 않는다. 기본 리뷰는 항상 최대 3개 패스로 유지한다.
- 사용자가 "오직 X", "X만"처럼 차원을 좁히면 기본값을 쓰지 않고 요청한 차원만 활성화한다.
- 사용자가 "X도 봐줘"처럼 추가 차원을 요청할 때만 기본 활성 차원에 해당 차원을 추가한다.
- 사용자가 특정 차원을 빼 달라고 하면 기본 집합에서 제거한다.
- 비활성 7개 차원은 사용자가 명시적으로 요청할 때만 활성화한다.

### coffee-order-system 차원 잠금 기준

- 리뷰 시작 전에 `README.md`, `build.gradle`, 실제 `src/`를 확인한다. 문서의 `설계 확정`이나 채택된 ADR을 구현 완료로 간주하지 않는다.
- `correctness`는 포인트 음수 방지, 오버플로, 멱등 재생, 락 순서, 원자적 쓰기,
  Outbox lease·claim token, `[from, to)` 시간 경계 같은 로직 불변 조건만 본다.
- `conventions`는 루트·하위 `AGENTS.md`와 `docs/CONVENTIONS.md`를 우선하고,
  기능별 패키지·의존 방향·UTC `Clock`·Spotless 규칙을 확인한다.
- `test-coverage`는 정상 경로뿐 아니라 경계값, 롤백, 중복 요청, 동시 실행, 외부 실패를 확인한다.
  DB 락·제약·트랜잭션은 H2나 mock이 아니라 MySQL Testcontainers 검증이 있는지 본다.
- `architecture`는 MySQL 정본, Facade와 Service 책임, 외부 네트워크 호출의 트랜잭션 분리,
  Phase 1 우선순위를 확인한다. Phase 1 변경에 Kafka·Redis·분산 락·MSA를 선행 제안하지 않는다.
- `security`를 요청받으면 입력 검증·SQL·Flyway·런타임 설정·로그·secret·외부 어댑터를 본다.
  V1에서 인증·인가가 제외된 사실 자체는 Finding으로 만들지 않는다.
- `cross-file-consistency`는 코드와 `build.gradle`, Flyway migration, `docs/API.md`,
  `docs/ERD.md`, `docs/ARCHITECTURE.md`, 관련 ADR이 같은 계약을 말하는지 본다.
- `behavioral-correctness`는 `docs/PRD.md`의 수용 시나리오와 `docs/API.md`의 상태·오류·이벤트 계약을 실제 코드 경로와 테스트에 대조한다.
- `performance`를 요청받으면 캐시를 먼저 제안하지 말고 N+1, 인덱스 적합성, 락 범위,
  네트워크 호출 위치, polling·retry의 무제한 실행부터 확인한다.

## 실행 절차

### 1. 리뷰 범위 1회 계산 (오케스트레이터)

```shell
git status --short
git diff --stat
git diff --cached --stat
git diff --name-only
git diff --cached --name-only
git ls-files --others --exclude-standard
```

- 대상이 브랜치 변경사항이면 base를 자동 감지한다: `git symbolic-ref --quiet refs/remotes/origin/HEAD`(없으면 `git remote show origin`에서 HEAD branch 확인, 그래도 없으면 `main`→`master` 순으로 존재하는 것)를 base로 삼아 `git diff --stat <base>...HEAD`와 `git diff --name-only <base>...HEAD`를 실행한다. 사용자가 base를 명시하면 그것을 우선한다.
- 브랜치 diff만으로 작업 트리가 깨끗하다고 판단하지 않는다. unstaged·staged·untracked 목록을 합쳐 실제 리뷰 범위를 만들고, untracked 파일은 전체 내용을 diff처럼 취급한다.
- 특정 파일/PR을 지정했으면 그 범위로 좁힌다.
- 통계를 본 뒤 실제 diff는 필요한 파일 범위로만 확보한다.

### 2. 차원별 리뷰 실행

활성 차원마다 독립 리뷰 패스를 만든다.
Claude Code에서는 `Agent` 같은 subagent 도구를, Codex에서는 사용 가능한 multi-agent/sub-agent 도구를 우선 사용한다.
활성 차원이 동시 실행 슬롯보다 많으면 슬롯 수에 맞춰 배치로 실행한다.
도구가 없으면 메인 에이전트가 활성 차원을 하나씩 순차 리뷰하되, 각 차원별로 아래 입력을 그대로 적용한다.
각 차원 프롬프트에 아래를 주입한다 — 독립 리뷰 패스가 범위를 재해석하지 않게 한다:

- **리뷰 범위**: 변경 파일 목록 + (필요 시) 핵심 diff. 개별 리뷰 패스가 git을 재실행하지 않아도 되게 명시.
- **차원 잠금**: "너는 오직 `<차원>`만 본다. 다른 차원 문제는 무시하라."
- **read-only 못박기**: "코드를 수정하지 마라. 파일 수정 도구와 쓰기 명령 금지. 발견만 보고하라."
- **컨벤션 소스**(conventions 차원 한정): 레포의 컨벤션 문서를 기준으로 삼아라. 존재하는 것을 순서대로 찾는다 — `AGENTS.md`/`CLAUDE.md`가 가리키는 컨벤션 정본, `docs/CONVENTIONS.md`, `CONTRIBUTING.md`, `.editorconfig`, 린터·포매터 설정(`.eslintrc*`, `ruff.toml`, `checkstyle`, `spotless` 등). 명시된 컨벤션 문서가 없으면 해당 언어의 관용을 기준으로 삼는다.
- **반환 형식**: 아래 "서브에이전트 반환 형식"을 그대로 지시.

### 3. 병합·우선순위화 (오케스트레이터)

- 각 차원별 Findings를 모아 중복 제거(같은 위치·같은 문제는 하나로).
- severity 순으로 정렬(아래 "심각도" 순서).
- 차원 간 상충(예: 성능 vs 가독성)은 트레이드오프로 명시.

## 심각도 (4단계)

| | 레벨 | 의미 |
| --- | --- | --- |
| 🔴 | `critical` | 머지 차단. 데이터 손상·보안 취약·크래시·명세 위반. |
| 🟠 | `major` | 머지 전 수정 권장. 실질 버그·설계 결함. |
| 🟡 | `minor` | 개선 권장하나 차단 아님. |
| ⚪ | `nit` | 취향·사소. 무시 가능. |

## 출력: 2층

read-only 리뷰다. 아래는 **텍스트로 출력**한다. GitHub PR에 실제 코멘트로 다는 것은 사용자가 명시 요청할 때만 `gh`로 한다.

### 1층 — 라인별 코멘트 (발견마다 1개)

각 Finding을 파일·라인에 앵커해 아래 4종을 그대로 쓴다:

```
<이모지> <레벨> | <제목>            (예: 🔴 critical | 결제 확정 전 재고 차감 누락)
TL;DR: 무엇이 / 왜 문제인지 한 줄.
Good: 이 코드에서 잘 된 점 또는 지키려던 의도 한 줄 (비난 완화·맥락).
→ Fix:
```<lang>
<수정 제안 코드>
```
```

`nit`은 코드 블록 없이 한 줄 제안만 달아도 된다.

### 2층 — PR 전체 요약 (1개)

```
## 판정: Approve | Changes Requested | Blocked
심각도: 🔴 <n>  🟠 <n>  🟡 <n>  ⚪ <n>
Walkthrough: 이번 변경이 무엇을 하는지 2~3줄.
잘된 점:
- ...
주요 지적 (critical/major만):
- 🔴 file:line — 제목
- 🟠 file:line — 제목
다음 액션:
- ...
```

판정 규칙: `critical` 하나라도 있으면 **Blocked**, 없고 `major` 있으면 **Changes Requested**, 둘 다 없으면 **Approve**(minor/nit은 코멘트로만).

직설적으로 쓰되 비난하지 않는다. 필요할 때만 짧은 예시 코드를 든다.

## 차원별 반환 형식

각 리뷰 패스는 자기 차원의 Findings만 "1층 — 라인별 코멘트" 형식으로 반환한다(차원명을 머리에 붙인다). 발견 없으면 `No findings.`.
오케스트레이터가 이를 모아 2층 요약을 만든다.

## 기본 규칙

- 코드를 직접 수정하지 않는다. 전체 구현 코드를 제공하지 않는다.
- 확인하지 않은 검증(테스트 통과 등)을 완료로 표시하지 않는다.
- 차원별 결과가 근거 없이 단정하면 오케스트레이터가 걸러낸다(위치·근거 없는 Finding은 낮춰 다룬다).
- 판단 기준(특히 과한 설계 제한, 컨벤션)은 위 "컨벤션 소스"에서 찾은 레포의 컨벤션 문서를 단일 소스로 따른다. 여기 중복 기재하지 않는다.
