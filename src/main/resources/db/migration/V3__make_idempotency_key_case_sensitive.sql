ALTER TABLE idempotency_requests
    MODIFY COLUMN idempotency_key VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
