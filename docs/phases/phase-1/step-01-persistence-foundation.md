# Step 01. MySQL 영속성 기반 구성

- PR 제목 예시: `chore: add mysql persistence foundation`
- branch 예시: `feature/phase1-step-01-persistence`
- 선행 step: 없음

## 목표

현재 외부 DB 없이 기동되는 골격을 MySQL 8.x 기반 애플리케이션으로 전환하고, 빈 DB에서 Phase 1 전체 스키마와 초기 데이터를 재현할 수 있게 한다.

## 구현 범위

- `build.gradle`에 Spring Data JPA, MySQL Connector/J, Flyway MySQL 지원과 MySQL Testcontainers 의존성을 추가한다.
- 구현 시점에 실제 pull·기동을 확인한 MySQL 8.x patch 이미지 태그를 고정하고 Testcontainers 코드와 로컬 `bootRun`용 컨테이너가 정확히 같은 태그를 사용하게 한다.
- datasource, Flyway, Hibernate 검증, JDBC UTC timezone 설정을 환경 변수로 주입할 수 있게 한다.
- 고정한 이미지로 로컬 MySQL 컨테이너를 시작·종료하는 Windows PowerShell과 POSIX 셸별 정확한 명령 및 `bootRun`에 필요한 datasource 환경 변수를 같은 PR에서 `README.md`와 `docs/COMMANDS.md`에 기록한다.
- `docs/ERD.md`의 일곱 테이블, FK, 유니크·체크 제약과 인덱스를 첫 Flyway migration으로 생성한다.
- 사용자, 활성·비활성 메뉴, 사용자별 0P 지갑을 별도 seed migration으로 생성한다.
- 기존 context test를 실제 MySQL Testcontainer와 Flyway를 사용하는 기반 통합 테스트로 전환한다.
- 테스트가 공유할 최소 Testcontainers 부트스트랩을 만든다. 범용 테스트 프레임워크는 만들지 않는다.

## 주요 변경 예상 경로

- `build.gradle`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/`
- `src/test/java/com/coffeeorder/`
- `README.md`
- `docs/COMMANDS.md`

## 테스트

- 빈 MySQL에서 Flyway migration이 순서대로 성공한다.
- 모든 테이블, 주요 제약과 인덱스가 생성된다.
- seed 사용자마다 지갑이 정확히 한 행 존재한다.
- 활성·비활성 메뉴 seed가 구분된다.
- 음수 잔액, 0 이하 메뉴 가격처럼 핵심 CHECK 위반이 실제 MySQL에서 거절된다.
- 애플리케이션 context가 Testcontainer datasource와 함께 로드된다.

## 수용 기준

- [ ] JPA가 스키마 생성의 정본이 아니며 Flyway가 빈 DB를 완전히 재현한다.
- [ ] DB 시각 컬럼과 JDBC session timezone이 UTC 계약을 따른다.
- [ ] 지갑 행은 사용자 생성과 함께 준비되어 지연 생성이 필요 없다.
- [ ] 테스트에서 H2를 사용하지 않는다.
- [ ] `docker version` 출력의 `Server` 섹션으로 Docker daemon 연결을 확인한 뒤 Testcontainers와 로컬 실행을 검증한다.
- [ ] Testcontainers와 로컬 `bootRun`용 컨테이너가 고정한 하나의 MySQL patch 이미지 태그를 사용한다.
- [ ] 실제 검증한 로컬 컨테이너 시작·종료 명령과 datasource 환경 변수가 `README.md`와 `docs/COMMANDS.md`에 정확히 명시된다.
- [ ] 실제 MySQL로 `bootRun` 후 `/actuator/health`가 `UP`이다.

## 제외 범위

- JPA Entity와 Repository 구현
- 공개 업무 API
- Kafka, Redis, Mock 데이터 플랫폼
- 운영 배포용 Docker/CI 구성
