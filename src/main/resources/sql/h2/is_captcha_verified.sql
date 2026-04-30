SELECT verified
FROM captcha_tokens
WHERE token = ? AND expires_at > CURRENT_TIMESTAMP
