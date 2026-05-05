UPDATE accounts
SET trusted_ip_login_enabled = ?, updated_at = CURRENT_TIMESTAMP
WHERE uuid = ?
