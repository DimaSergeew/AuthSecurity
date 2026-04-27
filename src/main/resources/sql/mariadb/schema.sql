CREATE TABLE IF NOT EXISTS accounts (
    uuid       CHAR(36)     NOT NULL PRIMARY KEY,
    username   VARCHAR(16)  NOT NULL,
    username_key VARCHAR(16),
    hash       VARCHAR(256) NOT NULL,
    last_ip    VARCHAR(64),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_accounts_username_key (username_key),
    INDEX idx_accounts_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS username_key VARCHAR(16);

UPDATE accounts
SET username_key = LOWER(username)
WHERE username_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_username_key
ON accounts (username_key);
