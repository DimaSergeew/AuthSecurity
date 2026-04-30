SELECT uuid, username, hash, last_ip, created_at, updated_at, captcha_verified_at
FROM accounts
WHERE username_key = LOWER(?)
