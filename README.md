# Coffee Order System

다중 서버·다중 인스턴스 환경에서도 포인트와 주문의 정합성을 유지하는 커피 주문 시스템 프로젝트입니다.

## 빠른 시작

### 준비 사항

- JDK 21
- IntelliJ IDEA
- Git

Gradle 8.14.5 Wrapper를 저장소에 포함하므로 Gradle은 별도로 설치하지 않아도 됩니다.

### 코드 포맷과 Git hook

저장소를 clone하거나 Git 저장소로 초기화한 뒤, clone마다 한 번 아래 명령으로 공유 hook을 활성화합니다.

```powershell
.\scripts\install-git-hooks.ps1
```

pre-commit hook은 staged 파일의 공백 오류와 전체 코드 포맷을 검사하며 파일을 자동으로 바꾸지는 않습니다. 포맷 검사가 실패하면 다음 명령으로 수정한 뒤 변경 내용을 다시 확인하고 stage합니다.

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
```

### IntelliJ에서 실행

1. IntelliJ에서 이 저장소의 루트 폴더를 엽니다.
2. Gradle 프로젝트 가져오기가 끝날 때까지 기다립니다.
3. Project SDK와 Gradle JVM이 JDK 21인지 확인합니다.
4. `CoffeeOrderSystemApplication`의 `main` 메서드를 실행합니다.
5. 브라우저에서 [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)를 열어 `{"status":"UP"}`을 확인합니다.

### PowerShell에서 실행

```powershell
.\gradlew.bat bootRun
```

다른 PowerShell 창에서 상태를 확인할 수 있습니다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

포맷 검사, 테스트와 전체 빌드는 다음 명령으로 실행합니다.

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat test
.\gradlew.bat clean build
```

현재 초기 설정은 외부 DB 없이 기동됩니다. JPA, MySQL, Flyway는 다음 구현 단계에서 함께 연결합니다.

## 목표

- 커피 메뉴 목록 조회
- 포인트 충전
- 포인트 기반 주문·결제
- 결제 주문의 데이터 플랫폼 전달
- 직전 168시간 인기 메뉴 상위 3개 조회
- 동시성, 데이터 일관성, 다중 인스턴스, 예외와 테스트 고려

## 문제 해결 전략

### 포인트와 주문 정합성

여러 애플리케이션 인스턴스가 공유하는 MySQL을 정합성의 기준으로 사용합니다. 같은 사용자의 충전과 주문은 `point_wallets` 행을 `SELECT ... FOR UPDATE`로 잠그고, 잔액 변경·원장·주문·Outbox를 짧은 트랜잭션 안에서 처리합니다.

### 중복 요청

충전과 주문 API에 `Idempotency-Key`를 필수로 적용합니다. `(userId, operation, key)` DB 유니크 제약과 요청 해시, 최초 완료 응답 스냅샷을 사용해 재시도가 중복 충전·결제를 만들지 않게 합니다.

### 외부 데이터 전달

외부 API를 주문 트랜잭션 안에서 호출하지 않습니다. 주문과 `ORDER_PAID` Outbox 이벤트를 함께 커밋한 뒤 별도 작업자가 중복 가능한 at-least-once 방식으로 발행합니다. Phase 1은 Mock HTTP, Phase 2는 Kafka를 사용합니다.

### 인기 메뉴 정확성

인기 메뉴는 Redis나 Kafka의 비동기 카운터가 아니라 MySQL의 `PAID` 주문 원본을 직접 집계합니다. 기준은 한 번 고정한 UTC 시각의 `[to - 168시간, to)`이며, 주문 횟수 내림차순과 메뉴 ID 오름차순으로 최대 3개를 선택합니다.

### 캐시

Redis는 핵심 기능을 완성한 뒤 활성 메뉴 목록에만 Cache-Aside로 적용합니다. Redis는 정본이 아니며 장애 시 MySQL로 폴백합니다.

### 검증

H2 대신 MySQL Testcontainers로 비관적 락, 유니크 제약, 트랜잭션 롤백, `SKIP LOCKED`와 lease 회수를 검증합니다. 빠른 도메인 단위 테스트와 실제 DB 통합·동시성 테스트를 분리합니다.

## 문서

| 문서 | 역할 |
| --- | --- |
| [PRD](./docs/PRD.md) | 제품 목표, 범위, 비즈니스 규칙, 수용 기준 |
| [Architecture](./docs/ARCHITECTURE.md) | 시스템 구조, 트랜잭션, 동시성, 장애와 테스트 전략 |
| [Conventions](./docs/CONVENTIONS.md) | 패키지 의존 방향, 계층별 책임, 구현·테스트 규칙 |
| [ERD](./docs/ERD.md) | 테이블, 관계, 제약, 인덱스와 상태 전이 |
| [API](./docs/API.md) | HTTP·이벤트 요청/응답과 오류 계약 |
| [ADR](./docs/adr/README.md) | 대안과 기술적 선택 이유 |

## 주요 기술 선택

| 영역 | 선택 | 이유 |
| --- | --- | --- |
| 런타임 | Java 21, Spring Boot 3.5.16 | 과제 구현과 테스트 생태계 |
| 빌드 | Gradle Wrapper 8.14.5 | 별도 Gradle 설치 없는 재현 가능한 빌드 |
| 영속성 | JPA, MySQL 8.x | 트랜잭션·행 락·제약을 공유하는 정본 |
| 마이그레이션 | Flyway | 스키마와 초기 사용자·메뉴 재현 |
| 동시성 | MySQL 비관적 락 | 동일 사용자 포인트 쓰기 직렬화 |
| 중복 방지 | DB 기반 멱등성 | 다중 인스턴스와 재시작에도 효과 유지 |
| 외부 전달 | Transactional Outbox | 주문 커밋과 내구성 있는 이벤트 기록의 원자성 |
| 테스트 | JUnit 5, Testcontainers | 실제 MySQL 동작 검증 |
| Phase 2 | Kafka, Redis | 이벤트 전달 채널과 메뉴 조회 캐시 |

상세한 대안과 트레이드오프는 [ADR 인덱스](./docs/adr/README.md)에 기록했습니다.
