-- Opaque refresh tokens are only stored as HMAC-SHA256 hex (see RefreshTokenHasher); plain value is never persisted.
DELETE FROM refresh_tokens;

ALTER TABLE refresh_tokens
    DROP COLUMN token;

ALTER TABLE refresh_tokens
    ADD COLUMN token_hash VARCHAR(64) NOT NULL;

CREATE UNIQUE INDEX ux_refresh_tokens_token_hash ON refresh_tokens (token_hash);
