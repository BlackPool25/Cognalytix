-- Semantic clustering keys for cross-time patterns; post-entry mirror storage

ALTER TABLE user_topic_labels
    ADD COLUMN family_key VARCHAR(100);

UPDATE user_topic_labels
SET family_key = normalized_key
WHERE family_key IS NULL;

ALTER TABLE user_topic_labels
    ALTER COLUMN family_key SET NOT NULL;

CREATE INDEX idx_user_topic_labels_family ON user_topic_labels (user_id, family_key);

ALTER TABLE user_emotion_labels
    ADD COLUMN family_key VARCHAR(100);

UPDATE user_emotion_labels
SET family_key = normalized_key
WHERE family_key IS NULL;

ALTER TABLE user_emotion_labels
    ALTER COLUMN family_key SET NOT NULL;

CREATE INDEX idx_user_emotion_labels_family ON user_emotion_labels (user_id, family_key);

CREATE TABLE growth_insights (
    id               UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    insight_type     VARCHAR(20)  NOT NULL,
    trigger_entry_id UUID         REFERENCES journal_entries (id) ON DELETE SET NULL,
    topic_label_id   UUID         REFERENCES user_topic_labels (id) ON DELETE SET NULL,
    topic_family     VARCHAR(100),
    emotion_family   VARCHAR(100),
    pattern_data     JSONB        NOT NULL,
    narration        TEXT         NOT NULL,
    direction        VARCHAR(15),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_growth_insights_user ON growth_insights (user_id, insight_type, created_at DESC);

CREATE UNIQUE INDEX uq_growth_post_entry_trigger ON growth_insights (trigger_entry_id)
    WHERE insight_type = 'POST_ENTRY';
