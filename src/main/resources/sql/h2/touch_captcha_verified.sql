UPDATE accounts
SET captcha_verified_at = CURRENT_TIMESTAMP, captcha_verified_ip = ?
WHERE uuid = ?
