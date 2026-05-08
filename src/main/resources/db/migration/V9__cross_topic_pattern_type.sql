-- Add pattern type for distinguishing insight categories in growth_insights
ALTER TABLE growth_insights
    ADD COLUMN pattern_type VARCHAR(30);

COMMENT ON COLUMN growth_insights.pattern_type IS
    'CAUSAL_LINKAGE | EMOTION_SPILLOVER | PARALLEL_SHIFTS | EMOTION_DRIFT_ON_TOPIC_FAMILY | NONE';

-- Backfill pattern_type for existing rows
UPDATE growth_insights
SET pattern_type = 'EMOTION_DRIFT_ON_TOPIC_FAMILY'
WHERE pattern_type IS NULL;