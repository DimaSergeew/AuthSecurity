UPDATE accounts
SET hash = ?, updated_at = CURRENT_TIMESTAMP
WHERE uuid = ?
