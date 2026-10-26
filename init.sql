CREATE TABLE IF NOT EXISTS user_points_records (
    record_id          BIGINT        NOT NULL AUTO_INCREMENT,
    user_id            VARCHAR(64)   NOT NULL COLLATE utf8mb4_0900_bin COMMENT 'Case-sensitive; compared byte for byte',
    amount             BIGINT        NOT NULL COMMENT 'Points added, 1 to 1,000,000; deductions are not supported',
    reason             VARCHAR(255)  NULL,
    created_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    idempotency_key    VARCHAR(128)  CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_hash       CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
    original_response  JSON          NULL COMMENT 'Immutable original POST response for retries, even after reason edits',
    PRIMARY KEY (record_id),
    UNIQUE KEY uk_records_idempotency_key (idempotency_key),
    INDEX idx_user_id_created_at (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_points (
    user_id     VARCHAR(64)  NOT NULL COLLATE utf8mb4_0900_bin COMMENT 'Case-sensitive; compared byte for byte',
    total       BIGINT       NOT NULL DEFAULT 0 COMMENT 'Updated by an atomic upsert; overflow is rejected as TOTAL_LIMIT_EXCEEDED',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    INDEX idx_total (total)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS outbox_message (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    event_id     CHAR(36)      NOT NULL COMMENT 'UUID that consumers deduplicate on; equals eventId in the payload',
    topic        VARCHAR(127)  NOT NULL,
    tag          VARCHAR(64)   NOT NULL COMMENT 'RocketMQ tag, e.g. ADDED',
    message_key  VARCHAR(64)   NOT NULL COMMENT 'Indexed by RocketMQ for looking messages up, e.g. userId',
    request_id   VARCHAR(64)   NULL     COMMENT 'X-Request-Id of the request that wrote the message, for tracing',
    payload      JSON          NOT NULL COMMENT 'Message body; MySQL may reorder keys and whitespace',
    created_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS outbox_dead_letter (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    outbox_id    BIGINT        NOT NULL COMMENT 'Id the message had in outbox_message, for tracing only',
    event_id     CHAR(36)      NOT NULL COMMENT 'Not unique: a replayed message that is refused again gets a new row',
    topic        VARCHAR(127)  NOT NULL,
    tag          VARCHAR(64)   NOT NULL,
    message_key  VARCHAR(64)   NOT NULL,
    request_id   VARCHAR(64)   NULL,
    payload      JSON          NOT NULL,
    error        VARCHAR(500)  NULL     COMMENT 'The reason of the client or broker refused the message',
    created_at   DATETIME(3)   NOT NULL,
    dead_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_event_id (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
