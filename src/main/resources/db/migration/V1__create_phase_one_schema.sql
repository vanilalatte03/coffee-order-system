CREATE TABLE users (
    id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id)
);

CREATE TABLE menus (
    id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    price BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_menus PRIMARY KEY (id),
    CONSTRAINT chk_menus_price_positive CHECK (price > 0),
    CONSTRAINT chk_menus_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    INDEX idx_menus_status_id (status, id)
);

CREATE TABLE point_wallets (
    user_id BIGINT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_point_wallets PRIMARY KEY (user_id),
    CONSTRAINT fk_point_wallets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_point_wallets_balance_nonnegative CHECK (balance >= 0)
);

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    menu_name_snapshot VARCHAR(100) NOT NULL,
    unit_price BIGINT NOT NULL,
    quantity INT NOT NULL,
    paid_amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    paid_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_menu FOREIGN KEY (menu_id) REFERENCES menus (id),
    CONSTRAINT chk_orders_unit_price_positive CHECK (unit_price > 0),
    CONSTRAINT chk_orders_quantity_one CHECK (quantity = 1),
    CONSTRAINT chk_orders_paid_amount CHECK (paid_amount = unit_price * quantity),
    CONSTRAINT chk_orders_status CHECK (status = 'PAID'),
    INDEX idx_orders_popular (status, paid_at, menu_id),
    INDEX idx_orders_user_paid (user_id, paid_at, id)
);

CREATE TABLE point_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    type VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_point_transactions PRIMARY KEY (id),
    CONSTRAINT fk_point_transactions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_point_transactions_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uk_point_transactions_order_id UNIQUE (order_id),
    CONSTRAINT chk_point_transactions_type CHECK (type IN ('CHARGE', 'PAYMENT')),
    CONSTRAINT chk_point_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_point_transactions_balance_nonnegative CHECK (balance_after >= 0),
    CONSTRAINT chk_point_transactions_order_type CHECK (
        (type = 'CHARGE' AND order_id IS NULL)
        OR (type = 'PAYMENT' AND order_id IS NOT NULL)
    ),
    INDEX idx_point_transactions_user_created (user_id, created_at, id)
);

CREATE TABLE idempotency_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    operation VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_status SMALLINT NULL,
    response_body JSON NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT pk_idempotency_requests PRIMARY KEY (id),
    CONSTRAINT fk_idempotency_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_idempotency_scope UNIQUE (user_id, operation, idempotency_key),
    CONSTRAINT chk_idempotency_operation CHECK (operation IN ('POINT_CHARGE', 'ORDER_CREATE')),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED'))
);

CREATE TABLE outbox_events (
    event_id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    schema_version SMALLINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token CHAR(36) NULL,
    locked_by VARCHAR(100) NULL,
    locked_until DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id),
    CONSTRAINT uk_outbox_aggregate_event UNIQUE (aggregate_type, aggregate_id, event_type),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_outbox_event_type CHECK (
        aggregate_type = 'ORDER' AND event_type = 'ORDER_PAID'
    ),
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count BETWEEN 0 AND 11),
    INDEX idx_outbox_pending (status, next_attempt_at, created_at),
    INDEX idx_outbox_expired_lease (status, locked_until, created_at),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id)
);
