UPDATE captcha_tokens
SET verified = TRUE
WHERE token = ?
  AND (ip = ? OR ip IS NULL)
  AND expires_at > CURRENT_TIMESTAMP
