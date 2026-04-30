SELECT uuid, username, hash, last_ip, created_at, updated_at
FROM accounts
WHERE username_key = LOWER(?)
