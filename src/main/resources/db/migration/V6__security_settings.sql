-- Singleton row (singleton_key = 1) holding the effective server password pepper.
-- Seeded at startup from app.security.password-pepper if absent (see ApplicationRunner).

CREATE TABLE security_settings (
    singleton_key SMALLINT PRIMARY KEY CHECK (singleton_key = 1),
    password_pepper VARCHAR(8192) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
