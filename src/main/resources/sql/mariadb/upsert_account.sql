INSERT INTO accounts (uuid, username, hash, last_ip)
VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
    username = VALUES(username),
    hash     = VALUES(hash),
    last_ip  = VALUES(last_ip)
