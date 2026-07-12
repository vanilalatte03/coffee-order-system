package com.coffeeorder.domain.user.service;

import com.coffeeorder.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쓰기 유스케이스가 멱등성 행을 만들기 전에 사용하는 사용자 존재 사전 검증.
 *
 * <p>사용자 없음은 저장 대상이 아니므로 이 검증은 IdempotencyExecutor의 쓰기 트랜잭션 밖에서 호출된다.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 사용자 행이 없으면 저장 가능한 멱등 결과를 남기지 않고 즉시 거절한다. */
    @Transactional(readOnly = true)
    public void validateExists(long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
    }
}
