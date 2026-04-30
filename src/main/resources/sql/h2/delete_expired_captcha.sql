DELETE FROM captcha_tokens
WHERE expires_at <= CURRENT_TIMESTAMP
