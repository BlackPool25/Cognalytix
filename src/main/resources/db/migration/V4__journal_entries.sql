CREATE TABLE journal_entries (
    id               UUID PRIMARY KEY                  DEFAULT gen_random_uuid(),
    user_id          UUID                     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title            VARCHAR(255)             NOT NULL,
    content          TEXT                     NOT NULL,
    word_count       INT                      NOT NULL DEFAULT 0,
    analysis_status  VARCHAR(20)              NOT NULL DEFAULT 'PENDING',
    deleted_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
    CONSTRAINT journal_entries_analysis_status_check CHECK (analysis_status IN ('PENDING', 'DONE', 'FAILED'))
);

CREATE INDEX idx_entries_user_deleted ON journal_entries (user_id, deleted_at);
