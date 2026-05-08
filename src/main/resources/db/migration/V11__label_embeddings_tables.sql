-- Separate embedding tables for topics and emotions
-- mxbai-embed-large uses 1024 dimensions

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE topic_label_embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label_id        UUID NOT NULL REFERENCES user_topic_labels(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    embedding       vector(1024) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (label_id)
);

CREATE INDEX idx_topic_embeddings_user ON topic_label_embeddings (user_id);
CREATE INDEX idx_topic_embeddings_embedding
    ON topic_label_embeddings USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE TABLE emotion_label_embeddings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    label_id        UUID NOT NULL REFERENCES user_emotion_labels(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    embedding       vector(1024) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (label_id)
);

CREATE INDEX idx_emotion_embeddings_user ON emotion_label_embeddings (user_id);
CREATE INDEX idx_emotion_embeddings_embedding
    ON emotion_label_embeddings USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);