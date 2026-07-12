# 수동 HTTP 테스트

`coffee-order-happy-path.http`는 IntelliJ HTTP Client에서 실행하는 Phase 1 정상 흐름 시나리오다.

## 사전 준비

저장소 루트에서 MySQL을 시작한다.

```powershell
docker compose up -d --wait mysql
```

IntelliJ에서 `CoffeeOrderSystemApplication`을 실행하고 `http://localhost:8080/actuator/health`가 `UP`인지 확인한다.

## 실행 방법

1. IntelliJ에서 `coffee-order-happy-path.http`를 연다.
2. 파일 상단의 **Run All Requests in File**을 실행한다.
3. Services 창의 HTTP Client 실행 결과에서 모든 응답 handler test가 통과했는지 확인한다.

개별 요청을 실행할 때는 위에서 아래 순서로 실행한다. 충전과 주문의 재요청 단계는 바로 앞 단계에서 생성한 멱등 키와 최초 응답을 사용한다.

## 시나리오

1. 애플리케이션 Health 확인
2. 활성 메뉴 목록 확인
3. seed 사용자 `10`에게 `10,000P` 충전
4. 같은 충전 요청 재전송과 멱등 응답 검증
5. 카페라떼(`menuId=2`, `5,000P`) 주문·결제
6. 같은 주문 재전송과 중복 결제 방지 검증
7. 카페라떼가 인기 메뉴 집계에 포함되는지 확인

시나리오를 다시 실행하면 새 멱등 키를 생성하므로 반복 실행할 수 있다. 한 번 실행할 때 실제 잔액 변화는 `+5,000P`이며 재요청 단계는 잔액이나 주문 건수를 추가로 변경하지 않는다.

이 파일은 로컬 REST smoke와 멱등 재요청 확인을 위한 수동 테스트다. DB 동시성, 트랜잭션 롤백, Outbox 재시도와 인덱스 검증은 기존 JUnit·Testcontainers 테스트를 사용한다.
