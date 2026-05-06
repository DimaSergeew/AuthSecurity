UPDATE accounts
SET trusted_ip_login_enabled = ?,
    trusted_ip_login_opt_out = CASE WHEN ? THEN FALSE ELSE TRUE END,
    updated_at = CURRENT_TIMESTAMP
WHERE uuid = ?
