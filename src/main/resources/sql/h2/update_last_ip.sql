UPDATE accounts
SET last_ip = ?, updated_at = CURRENT_TIMESTAMP
WHERE uuid = ?
