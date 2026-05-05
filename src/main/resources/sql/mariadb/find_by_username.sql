SELECT uuid, username, hash, last_ip, created_at, updated_at, captcha_verified_at, captcha_verified_ip, trusted_ip_login_enabled
FROM accounts
WHERE username_key = LOWER(?)
