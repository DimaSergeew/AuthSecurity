SELECT uuid, username, hash, last_ip, created_at, updated_at, captcha_verified_at, captcha_verified_ip,
       NOT trusted_ip_login_opt_out AS trusted_ip_login_enabled
FROM accounts
WHERE username_key = LOWER(?)
