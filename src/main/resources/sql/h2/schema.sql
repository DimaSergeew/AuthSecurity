CREATE TABLE IF NOT EXISTS accounts (
    uuid                VARCHAR(36)  NOT NULL PRIMARY KEY,
    username            VARCHAR(16)  NOT NULL,
    username_key        VARCHAR(16)  NOT NULL UNIQUE,
    hash                VARCHAR(256) NOT NULL,
    last_ip             VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    captcha_verified_at TIMESTAMP    NULL,
    captcha_verified_ip VARCHAR(64)  NULL
);

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS captcha_verified_at TIMESTAMP NULL;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS captcha_verified_ip VARCHAR(64) NULL;

CREATE TABLE IF NOT EXISTS captcha_tokens (
    token       VARCHAR(64) NOT NULL PRIMARY KEY,
    uuid        VARCHAR(36) NOT NULL,
    username    VARCHAR(16) NOT NULL,
    ip          VARCHAR(64),
    verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_captcha_expires ON captcha_tokens(expires_at);
