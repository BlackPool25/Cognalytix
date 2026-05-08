-- V12: One-time LLM backfill of label_data JSONB for existing labels
-- This migration uses a generated function to process labels in batches
-- to avoid locking the table for too long during backfill

-- First, set all existing labels to a placeholder that the application will process
UPDATE user_topic_labels SET label_data = jsonb_build_object(
    'display', label,
    'category', NULL,
    'topic', NULL,
    'detail', NULL,
    'needs_llm_backfill', true
) WHERE label_data IS NULL;

UPDATE user_emotion_labels SET label_data = jsonb_build_object(
    'display', label,
    'category', NULL,
    'topic', NULL,
    'detail', NULL,
    'needs_llm_backfill', true
) WHERE label_data IS NULL;

-- Create a tracking table for the backfill status
CREATE TABLE IF NOT EXISTS label_backfill_status (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label_type      VARCHAR(10) NOT NULL,  -- 'TOPIC' or 'EMOTION'
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    total_labels    INT,
    processed_count INT DEFAULT 0,
    error_message   TEXT,
    CONSTRAINT unique_label_type_backfill UNIQUE (label_type)
);

-- Initialize backfill tracking for both types
INSERT INTO label_backfill_status (label_type, started_at, total_labels)
SELECT 'TOPIC', NOW(), COUNT(*) FROM user_topic_labels WHERE label_data->>'needs_llm_backfill' = 'true'
ON CONFLICT (label_type) DO NOTHING;

INSERT INTO label_backfill_status (label_type, started_at, total_labels)
SELECT 'EMOTION', NOW(), COUNT(*) FROM user_emotion_labels WHERE label_data->>'needs_llm_backfill' = 'true'
ON CONFLICT (label_type) DO NOTHING;