-- Hierarchical label storage via JSONB label_data column
-- Stores display text + hierarchy levels (category, topic, detail) for efficient queries

ALTER TABLE user_topic_labels
    ADD COLUMN label_data JSONB;

ALTER TABLE user_emotion_labels
    ADD COLUMN label_data JSONB;

-- Backfill label_data with display text; use COALESCE to handle any NULL labels in source data
UPDATE user_topic_labels
SET label_data = jsonb_build_object(
    'display', COALESCE(label, 'unknown'),
    'category', NULL,
    'topic', NULL,
    'detail', NULL,
    'needs_llm_backfill', true
)
WHERE label_data IS NULL;

UPDATE user_emotion_labels
SET label_data = jsonb_build_object(
    'display', COALESCE(label, 'unknown'),
    'category', NULL,
    'topic', NULL,
    'detail', NULL,
    'needs_llm_backfill', true
)
WHERE label_data IS NULL;

-- Add GIN indexes for JSONB queries on hierarchy levels
CREATE INDEX idx_topic_labels_label_data_gin
    ON user_topic_labels USING GIN (label_data);

CREATE INDEX idx_emotion_labels_label_data_gin
    ON user_emotion_labels USING GIN (label_data);

-- Index for querying by category
CREATE INDEX idx_topic_labels_category
    ON user_topic_labels ((label_data->>'category'));

CREATE INDEX idx_emotion_labels_category
    ON user_emotion_labels ((label_data->>'category'));

-- Index for querying by topic
CREATE INDEX idx_topic_labels_topic
    ON user_topic_labels ((label_data->>'topic'));

CREATE INDEX idx_emotion_labels_topic
    ON user_emotion_labels ((label_data->>'topic'));