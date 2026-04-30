UPDATE accounts
SET captcha_verified_at = CURRENT_TIMESTAMP
WHERE uuid = ?
