-- user_id uses utf8mb4_0900_bin: ids are compared byte for byte, so 'abc', 'ABC' and 'abc ' are different users.
-- The table default (utf8mb4_0900_ai_ci) is case- and accent-insensitive, and utf8mb4_bin ignores trailing spaces.
CREATE TABLE IF NOT EXISTS user_points_records (
    record_id  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    VARCHAR(64)  NOT NULL COLLATE utf8mb4_0900_bin,
    amount     BIGINT       NOT NULL,
    reason     VARCHAR(255) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (record_id),
    INDEX idx_user_id_created_at (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_points (
    user_id    VARCHAR(64) NOT NULL COLLATE utf8mb4_0900_bin,
    total      BIGINT      NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    INDEX idx_total (total)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
