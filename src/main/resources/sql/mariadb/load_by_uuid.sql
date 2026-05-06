SELECT uuid, username, hash, last_ip, created_at, updated_at, captcha_verified_at, captcha_verified_ip,
       CASE WHEN trusted_ip_login_opt_out THEN FALSE ELSE TRUE END AS trusted_ip_login_enabled
FROM accounts
WHERE uuid = ?
