SELECT uuid, username, hash, last_ip, created_at, updated_at, captcha_verified_at, captcha_verified_ip
FROM accounts
WHERE uuid = ?
