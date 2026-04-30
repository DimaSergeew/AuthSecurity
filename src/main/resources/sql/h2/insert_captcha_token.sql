INSERT INTO captcha_tokens (token, uuid, username, ip, expires_at)
VALUES (?, ?, ?, ?, DATEADD('SECOND', ?, CURRENT_TIMESTAMP))
