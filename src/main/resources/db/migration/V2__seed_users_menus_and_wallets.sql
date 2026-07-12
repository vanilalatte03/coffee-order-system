INSERT INTO users (id, created_at, updated_at)
VALUES
    (10, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (20, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO menus (id, name, price, status, created_at, updated_at)
VALUES
    (1, '아메리카노', 4500, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (2, '카페라떼', 5000, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (3, '카푸치노', 5500, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (4, '시즌 종료 메뉴', 6000, 'INACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO point_wallets (user_id, balance, created_at, updated_at)
SELECT id, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM users;
