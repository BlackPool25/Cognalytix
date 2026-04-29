CREATE TABLE mood_analyses (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id    UUID        NOT NULL UNIQUE REFERENCES journal_entries (id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    mood_label  VARCHAR(20) NOT NULL,
    intensity   SMALLINT    NOT NULL CHECK (intensity BETWEEN 1 AND 5),
    insight     TEXT        NOT NULL,
    coping_tip  TEXT,
    themes      JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mood_analyses_user_created ON mood_analyses (user_id, created_at);
