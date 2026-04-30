-- Per-user growing vocabulary for topic + emotion; sections map topic to emotion; entry telemetry

CREATE TABLE user_topic_labels (
    id             UUID         PRIMARY KEY   DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL     REFERENCES users (id) ON DELETE CASCADE,
    label          VARCHAR(200) NOT NULL,
    normalized_key VARCHAR(200) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL      DEFAULT NOW(),
    UNIQUE (user_id, normalized_key)
);

CREATE INDEX idx_user_topic_labels_user ON user_topic_labels (user_id);

CREATE TABLE user_emotion_labels (
    id             UUID         PRIMARY KEY   DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL     REFERENCES users (id) ON DELETE CASCADE,
    label          VARCHAR(200) NOT NULL,
    normalized_key VARCHAR(200) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL      DEFAULT NOW(),
    UNIQUE (user_id, normalized_key)
);

CREATE INDEX idx_user_emotion_labels_user ON user_emotion_labels (user_id);

ALTER TABLE journal_entries
    ADD COLUMN analysis_attempt_count  INT         NOT NULL DEFAULT 0,
    ADD COLUMN analysis_fail_count       INT         NOT NULL DEFAULT 0,
    ADD COLUMN analysis_in_progress      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN last_analysis_error       VARCHAR(64);

-- Allow user-defined emotional wording on aggregate row
ALTER TABLE mood_analyses
    ALTER COLUMN mood_label TYPE VARCHAR(200);

ALTER TABLE mood_analyses
    ADD COLUMN aggregate_emotion_label_id UUID REFERENCES user_emotion_labels (id) ON DELETE SET NULL;

CREATE TABLE journal_entry_sections (
    id                  UUID        PRIMARY KEY      DEFAULT gen_random_uuid(),
    entry_id            UUID        NOT NULL         REFERENCES journal_entries (id) ON DELETE CASCADE,
    user_id             UUID        NOT NULL         REFERENCES users (id) ON DELETE CASCADE,
    sort_order          INT         NOT NULL,
    topic_label_id      UUID        NOT NULL         REFERENCES user_topic_labels (id) ON DELETE RESTRICT,
    emotion_label_id    UUID        NOT NULL         REFERENCES user_emotion_labels (id) ON DELETE RESTRICT,
    content             TEXT        NOT NULL,
    intensity           SMALLINT    NOT NULL         CHECK (intensity BETWEEN 1 AND 5),
    created_at          TIMESTAMPTZ NOT NULL         DEFAULT NOW(),
    UNIQUE (entry_id, sort_order)
);

CREATE INDEX idx_journal_entry_sections_entry ON journal_entry_sections (entry_id);
CREATE INDEX idx_journal_entry_sections_user ON journal_entry_sections (user_id, created_at);
