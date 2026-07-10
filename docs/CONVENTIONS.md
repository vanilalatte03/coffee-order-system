# Coffee Order System Conventions

이 문서는 구현할 때 반복해서 적용할 코드 규칙을 정의한다. 제품 범위와 계약은 아래 정본 문서를 우선한다.

- 제품 요구사항: [PRD](./PRD.md)
- 패키지·트랜잭션·장애 전략: [ARCHITECTURE](./ARCHITECTURE.md)
- HTTP·이벤트 계약: [API](./API.md)
- 테이블·제약·인덱스: [ERD](./ERD.md)
- 선택 이유와 트레이드오프: [ADR](./adr/README.md)

문서가 충돌하면 더 구체적인 정본과 최신 ADR을 따르고, 구현과 문서를 같은 변경에서 맞춘다.

## 패키지와 의존 방향

기본 패키지는 `com.coffeeorder`다. `domain` 아래에서 `menu`, `point`, `order`, `ranking`, `outbox`, `idempotency`처럼 기능별로 묶고, 각 기능 안에는 필요한 패키지만 만든다. 여기서 `domain`은 순수 도메인 계층만 뜻하지 않고 업무 기능을 모으는 최상위 네임스페이스다.

```text
com/coffeeorder/
├─ domain/
│  └─ order/
│     ├─ controller/  # HTTP Controller
│     ├─ dto/         # HTTP Request/Response DTO
│     ├─ entity/      # JPA Entity, Value Object, Enum
│     ├─ repository/  # Spring Data JPA Repository
│     └─ service/     # Service, Facade, Command/Query/Result
├─ infra/             # HTTP, Kafka, Redis 외부 연동 구현
└─ global/            # 설정, 오류, 시간, 관측성
```

- `controller`는 같은 기능의 `service`에 있는 Service 또는 Facade만 호출한다.
- HTTP Request/Response는 `dto`에 두고, Service 내부 계약인 Command/Query/Result는 `service`에 별도 파일로 둔다.
- 도메인 Service는 자기 기능의 Repository만 직접 참조한다.
- 여러 기능을 가로지르는 흐름은 유스케이스를 소유한 기능의 `service`에 Facade로 둔다. Facade는 Repository를 직접 주입하지 않고 각 도메인 Service의 public 메서드를 조합한다.
- 주문·포인트·원장·Outbox·멱등 결과처럼 하나의 원자적 변경은 `OrderFacade`가 전체 유스케이스, 트랜잭션 범위와 락 순서를 소유한다. 실제 트랜잭션 실행과 유니크 충돌 후 재조회 경계는 `IdempotencyExecutor` Service에 위임할 수 있으며, 하위 Service는 같은 트랜잭션에 참여하고 독립 트랜잭션으로 원자성을 끊지 않는다.
- HTTP·Kafka·Redis 연동 계약은 사용하는 기능의 `service`에 두고 실제 구현은 `infra`에 둔다. Service와 Facade가 `infra` 구현 클래스에 직접 의존하지 않는다.
- `global`에는 설정, 오류 모델, 시간, 관측성처럼 둘 이상의 기능에서 실제로 공유하는 코드만 둔다. 미래의 재사용 가능성만으로 옮기지 않는다.

## 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| Controller | `{Feature}Controller` | `OrderController` |
| Service | `{Action}{Feature}Service` | `PlaceOrderService`, `ChargePointService` |
| Facade | `{Feature}Facade` | `OrderFacade` |
| Repository | `{Aggregate}Repository` | `OrderRepository` |
| 외부 연동 계약 | `{Capability}Port` 또는 역할명 | `OrderEventPublisher`, `MenuCache` |
| 외부 연동 구현 | `{Technology}{Role}Adapter` | `HttpOrderEventPublisherAdapter`, `RedisMenuCacheAdapter` |
| Request DTO | `{Action}{Feature}Request` | `PlaceOrderRequest` |
| Response DTO | `{Action}{Feature}Response` | `PlaceOrderResponse` |
| Command / Result | `{Action}{Feature}Command`, `{Action}{Feature}Result` | `ChargePointCommand`, `ChargePointResult` |
| Entity / Value Object | 단수 명사 | `Order`, `PointWallet` |
| Enum | `{Domain}Status`, `{Domain}Type` | `OrderStatus`, `PointTransactionType` |

- 메서드명은 행위를 드러낸다. `placeOrder()`, `chargePoint()`, `claimPendingEvents()`처럼 쓰고 의미가 모호한 `process()`, `handle()`, `check()`는 피한다.
- 변수명은 축약하지 않는다. `remainingPointBalance`, `idempotencyKey`처럼 도메인 의미를 보존한다.
- 같은 개념은 PRD, API, ERD와 동일한 용어를 사용한다. `point`, `order`, `outbox`, `idempotency`를 임의의 동의어로 바꾸지 않는다.

## Controller와 DTO

- Controller는 Request DTO 검증, 헤더·경로·본문 변환, Service 또는 Facade 호출, Response DTO 반환만 담당한다.
- 비즈니스 규칙, Repository 호출, 트랜잭션, 외부 API 호출을 Controller에 두지 않는다.
- 생성자 주입과 `private final` 필드를 사용하고 필드 주입은 사용하지 않는다.
- Entity나 영속성 모델을 API에 직접 노출하지 않는다.
- 응답 모양과 HTTP 상태·오류 코드는 [API](./API.md)를 따른다. 별도 공통 응답 래퍼를 임의로 추가하지 않는다.
- 예외 응답은 전역 예외 처리에서 일관되게 변환하며 내부 예외 메시지, SQL, stack trace를 노출하지 않는다.

## Service, Facade와 Transaction

- Service 메서드는 한 기능의 유스케이스를 표현한다. 단일 기능의 상태 변경은 Service가, 여러 기능을 묶는 상태 변경은 Facade가 트랜잭션 경계를 소유한다.
- 도메인 횡단 Facade는 Repository를 직접 주입하지 않고 Service의 public 메서드만 조합한다. 하위 Service는 Facade가 시작한 트랜잭션에 참여하고 `REQUIRES_NEW`로 원자성을 분리하지 않는다.
- 포인트 쓰기는 `입력·멱등성 → 지갑 락 → 도메인 쓰기 → 커밋` 순서를 지킨다. 주문은 [ARCHITECTURE](./ARCHITECTURE.md#52-포인트-비관적-락)에 정의된 잠금 순서를 바꾸지 않는다.
- 주문, 포인트 차감, 포인트 원장, Outbox, 멱등 완료 결과는 계약에 따라 한 트랜잭션에서 모두 커밋하거나 모두 롤백한다.
- 저장해야 하는 결정적 비즈니스 실패는 멱등 결과만 커밋해야 하므로, 무조건 예외를 던져 트랜잭션을 `rollback-only`로 만들지 않는다.
- 외부 HTTP·Kafka·Redis 호출은 지갑 락을 보유한 DB 트랜잭션 안에서 수행하지 않는다.
- 조회 전용 트랜잭션은 `readOnly = true`를 사용한다.
- 즉시 반영이 꼭 필요한 이유가 없다면 `saveAndFlush()`를 반복하지 않고 트랜잭션 커밋과 더티 체킹을 사용한다.
- 한 요청에서 필요한 현재 시각은 주입한 `Clock`으로 한 번만 캡처해 전달한다.

## Entity와 DTO

- Entity와 Service의 순수 정책은 잔액 부족, 양수 금액, 허용된 상태 전이 같은 불변 조건을 표현한다.
- Entity에 범용 setter를 열지 않고 `charge()`, `pay()`, `markPublished()`처럼 의도가 드러나는 변경 메서드를 둔다.
- HTTP Request/Response DTO, Service Command/Query/Result, Entity 모델의 책임을 분리한다.
- ID, 가격, 포인트는 `long` 범위를 사용하며 오버플로와 `0`·음수 경계를 검증한다.
- 주문에는 결제 시점의 메뉴 이름과 가격을 스냅샷으로 보존한다. 이후 현재 메뉴 정보로 과거 주문을 다시 계산하지 않는다.

## Repository와 외부 연동

- Repository는 각 기능의 `repository`에 두며 조회와 저장 계약만 제공하고 비즈니스 정책을 품지 않는다.
- 비관적 락, `SKIP LOCKED`, 기간 집계처럼 DB 동작이 계약의 일부인 쿼리는 이름과 테스트로 의도를 드러낸다.
- HTTP·Kafka·Redis 같은 외부 연동 계약은 사용하는 기능의 `service`에 두고, `infra` 구현은 기술 변환, timeout, 재시도 가능 오류 분류를 담당해 도메인 오류 계약으로 변환한다.
- MySQL이 핵심 상태의 정본이다. 프로세스 메모리, 로컬 락, Redis를 포인트·주문·멱등성·인기 순위의 정합성 근거로 사용하지 않는다.
- Outbox 발행은 중복 가능한 at-least-once 처리다. exactly-once라고 표현하지 않으며 `eventId` 중복 제거 계약을 유지한다.
- Redis 장애 시 메뉴 조회는 MySQL로 폴백하고 주문 시 가격과 상태는 MySQL에서 다시 확인한다.

## Validation과 Exception

- 구조적 입력 검증은 `@Valid`와 Bean Validation으로 처리한다.
- 도메인 규칙 검증은 Entity 또는 Service/Facade의 한 책임 지점에서 수행하며 같은 조건을 여러 계층에서 중복 검사하지 않는다.
- DB 제약은 최종 방어선이다. 유니크·외래 키·체크 제약 위반을 안정적인 API 오류 코드로 변환할 때 원래 SQL이나 내부 메시지를 노출하지 않는다.
- 경계값, 소유권, 상태 전이, 멱등 키 형식과 요청 해시는 정상 경로와 함께 테스트한다.
- 재시도 가능한 인프라 실패와 재시도해도 같은 결정적 비즈니스 실패를 구분한다.

## DB 스키마 변경

JPA, MySQL, Flyway가 구현에 추가된 뒤에는 다음 규칙을 적용한다.

- 테이블, 컬럼, 인덱스, 제약, 초기 데이터를 바꾸는 변경에는 Flyway migration을 포함한다.
- JPA Entity 변경만으로 스키마 변경을 대체하지 않는다.
- 이미 적용된 migration은 수정하지 않고 새 migration으로 보정한다.
- migration과 Entity는 [ERD](./ERD.md)의 이름, 타입, 제약, 인덱스와 함께 갱신한다.
- 모든 DB 시각은 UTC `DATETIME(6)`으로 저장하고 JDBC 세션 타임존도 UTC로 고정한다.

## 시간 처리

- 애플리케이션의 절대 시각은 `Instant`, 경과 시간은 `Duration`, 현재 시각 주입은 `Clock`을 사용한다.
- API 시각은 RFC 3339 UTC로 반환한다.
- 주문 `paidAt`과 같은 주문의 `ORDER_PAID.occurredAt`은 한 번 캡처한 동일한 시각을 사용한다.
- 기간 조회는 경계를 명시한다. 인기 메뉴의 168시간 범위는 `[from, to)`다.
- 테스트에서는 `Clock.fixed(...)`를 사용한다. 정렬·만료·재시도 테스트에 `Instant.now()`나 무제한 대기를 사용하지 않는다.

## 설정과 Logging

- 환경별 URL, timeout, 재시도 횟수, lease, 캐시 TTL은 `application.yml`과 환경 변수로 주입한다.
- 비밀번호, 토큰, secret은 저장소와 로그에 남기지 않는다.
- 로그는 `traceId`, `orderId`, `eventId`처럼 흐름을 추적할 식별자를 포함하되 요청·응답 전문과 민감정보는 기록하지 않는다.
- 예상 가능한 비즈니스 거절은 `WARN`이나 `ERROR`로 남발하지 않는다. 재시도 고갈, Outbox 격리, DB lock timeout처럼 운영 조치가 필요한 실패를 구분한다.

## 포맷과 줄바꿈

- 모든 텍스트 파일은 UTF-8과 파일 끝 개행을 사용하고 후행 공백을 남기지 않는다.
- 줄 끝은 `.gitattributes`를 기준으로 LF를 사용한다. Windows 배치 파일인 `*.bat`, `*.cmd`만 CRLF를 사용한다.
- 들여쓰기는 공백을 사용한다. Java와 Gradle은 4칸, YAML은 2칸을 기준으로 한다.
- Java 포맷의 정본은 Spotless의 Google Java Format AOSP 스타일이다. AOSP 스타일은 4칸 들여쓰기를 사용한다.
- Markdown에서도 줄 끝 공백을 사용하지 않는다. 강제 줄바꿈이 필요하면 빈 줄이나 명시적인 HTML 태그를 사용한다.
- 포맷 위반은 `.\gradlew.bat spotlessCheck`, 자동 수정은 `.\gradlew.bat spotlessApply`로 처리한다. 자동 수정 후에는 diff를 확인한다.
- clone마다 `.\scripts\install-git-hooks.ps1`을 한 번 실행한다. pre-commit hook은 staged 공백 오류와 `spotlessCheck`를 검사하며 파일을 자동 수정하거나 stage하지 않는다.

## 테스트

- 단위 테스트는 JUnit 5를 사용하고 Spring Context 없이 Entity, Service와 순수 정책을 빠르게 검증한다.
- 테스트 클래스는 `{Target}Test`, 테스트 메서드는 `조건_행위_기대결과`가 드러나는 이름을 사용한다.
- DB 락, 유니크 제약, Flyway, 트랜잭션, `SKIP LOCKED`는 H2나 Repository mock이 아니라 MySQL Testcontainers로 검증한다.
- 동시성 테스트는 서로 다른 connection과 트랜잭션, 시작 장벽, 제한 시간을 사용한다. 테스트 메서드 전체를 `@Transactional`로 감싸지 않는다.
- 비동기 테스트는 `Thread.sleep()` 대신 제한 시간이 있는 Awaitility 조건 검증을 사용한다.
- 성공 건수뿐 아니라 최종 잔액, 원장, 주문, 멱등 결과, Outbox 개수까지 함께 검증한다.
- 새 기능은 정상 경로, 경계값, 롤백, 중복 요청, 동시 실행, 외부 실패 중 해당하는 경로를 테스트한다.

현재 저장소의 기본 검증 명령은 다음과 같다. 별도 통합 테스트 task를 추가하면 이 문서와 README를 함께 갱신한다.

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat test
.\gradlew.bat clean build
```

## Javadoc과 주석

- 코드가 무엇을 하는지 반복하지 않고 선택 이유, 락 순서, 부수효과, 실패·재시도 계약을 설명한다.
- public Service/Facade의 중요한 유스케이스와 복잡한 도메인 규칙, 외부 연동 계약에는 필요한 경우 Javadoc을 단다.
- getter, 단순 위임, 자명한 Controller 매핑에는 기계적으로 주석을 붙이지 않는다.
- 구현만 보고 복원하기 어려운 기술 선택은 긴 주석 대신 ADR로 기록한다.

## 과한 설계 제한

- Phase 1의 Spring Boot, JPA, MySQL, Mock HTTP로 핵심 정합성과 테스트를 먼저 완성한다.
- Kafka와 Redis는 문서에 정한 Phase 2 경계에서 도입하며, 도입 전부터 추상화나 운영 구성을 과도하게 만들지 않는다.
- MSA, 분산 락, CQRS, Event Sourcing, 새 저장소는 실제 요구와 측정 근거가 생기면 ADR로 결정한다.
- 단순화가 API, DB 제약, 멱등성, 락 순서, Outbox 전달 계약을 깨뜨려서는 안 된다.

## 커밋

- 커밋은 리뷰 가능한 의미 단위로 나눈다.
- 커밋 메시지는 `type: subject` 형식을 사용한다.
- `type`은 `feat`, `fix`, `refactor`, `docs`, `test`, `chore` 중 하나를 사용한다.
- `subject`는 변경 의도가 드러나는 현재형 한 줄로 작성한다.
- 기능, 리팩터링, 문서 정리처럼 서로 다른 목적을 한 커밋에 섞지 않는다.

## 완료 기준

- `spotlessCheck`가 통과한다.
- 관련 테스트와 `clean build`가 통과한다.
- API·스키마·아키텍처 계약이 바뀌면 정본 문서와 ADR을 함께 갱신한다.
- 외부 호출이 DB 락 트랜잭션 안으로 들어오지 않았는지 확인한다.
- 멱등 재생, 롤백, 동시성, 시간 경계에 대한 기존 보장을 유지한다.
- 로그와 설정에 민감정보가 없는지 확인한다.
