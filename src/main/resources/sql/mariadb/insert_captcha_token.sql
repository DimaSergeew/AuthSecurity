INSERT INTO captcha_tokens (token, uuid, username, expires_at)
VALUES (?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND))
