MERGE INTO accounts (uuid, username, hash, last_ip, updated_at)
KEY (uuid)
VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
