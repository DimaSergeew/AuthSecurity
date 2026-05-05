INSERT INTO captcha_tokens (token, uuid, username, expires_at)
VALUES (?, ?, ?, DATEADD('SECOND', ?, CURRENT_TIMESTAMP))
