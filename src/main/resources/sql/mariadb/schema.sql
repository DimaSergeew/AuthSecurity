CREATE TABLE IF NOT EXISTS accounts (
    uuid                CHAR(36)     NOT NULL PRIMARY KEY,
    username            VARCHAR(16)  NOT NULL,
    username_key        VARCHAR(16)  NOT NULL UNIQUE,
    hash                VARCHAR(256) NOT NULL,
    last_ip             VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    captcha_verified_at TIMESTAMP    NULL DEFAULT NULL,
    captcha_verified_ip VARCHAR(64)  NULL DEFAULT NULL,
    trusted_ip_login_opt_out BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS captcha_verified_at TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS captcha_verified_ip VARCHAR(64) NULL DEFAULT NULL;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS trusted_ip_login_opt_out BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE accounts DROP COLUMN IF EXISTS trusted_ip_login_enabled;

CREATE TABLE IF NOT EXISTS captcha_tokens (
    token       VARCHAR(64) NOT NULL PRIMARY KEY,
    uuid        CHAR(36)    NOT NULL,
    username    VARCHAR(16) NOT NULL,
    ip          VARCHAR(64),
    verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_captcha_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
