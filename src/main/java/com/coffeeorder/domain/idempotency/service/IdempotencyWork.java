package com.coffeeorder.domain.idempotency.service;

/**
 * 멱등성 행과 같은 쓰기 트랜잭션에서 실행할 도메인 작업.
 *
 * <p>결정적 비즈니스 실패는 예외를 던져 롤백하지 않고 오류 snapshot을 반환해 완료 결과로 저장한다.
 */
@FunctionalInterface
public interface IdempotencyWork {

    IdempotencyResponseSnapshot execute();
}
