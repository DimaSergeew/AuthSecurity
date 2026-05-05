UPDATE captcha_tokens
SET verified = TRUE
WHERE token = ?
  AND expires_at > CURRENT_TIMESTAMP
