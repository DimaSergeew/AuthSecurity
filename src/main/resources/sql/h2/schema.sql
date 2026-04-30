CREATE TABLE IF NOT EXISTS accounts (
    uuid         VARCHAR(36)  NOT NULL PRIMARY KEY,
    username     VARCHAR(16)  NOT NULL,
    username_key VARCHAR(16)  NOT NULL UNIQUE,
    hash         VARCHAR(256) NOT NULL,
    last_ip      VARCHAR(64),
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
