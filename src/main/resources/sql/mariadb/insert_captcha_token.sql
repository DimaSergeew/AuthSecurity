INSERT INTO captcha_tokens (token, uuid, username, ip, expires_at)
VALUES (?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND))
