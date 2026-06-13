package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.EmotionLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.TopicLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EmbeddingStorageService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStorageService.class);

    private final OllamaEmbeddingModel embeddingModel;
    private final TopicLabelEmbeddingRepository topicEmbeddingRepository;
    private final EmotionLabelEmbeddingRepository emotionEmbeddingRepository;
    private final JdbcTemplate jdbc;

    public EmbeddingStorageService(
            OllamaEmbeddingModel embeddingModel,
            TopicLabelEmbeddingRepository topicEmbeddingRepository,
            EmotionLabelEmbeddingRepository emotionEmbeddingRepository,
            JdbcTemplate jdbc) {
        this.embeddingModel = embeddingModel;
        this.topicEmbeddingRepository = topicEmbeddingRepository;
        this.emotionEmbeddingRepository = emotionEmbeddingRepository;
        this.jdbc = jdbc;
    }

    @Async("analysisExecutor")
    public void storeTopicEmbeddingAsync(UserTopicLabel label) {
        try {
            storeTopicEmbedding(label);
        } catch (Exception e) {
            log.error("Failed to store topic embedding for label {}: {}", label.getId(), e.getMessage(), e);
        }
    }

    @Async("analysisExecutor")
    public void storeEmotionEmbeddingAsync(UserEmotionLabel label) {
        try {
            storeEmotionEmbedding(label);
        } catch (Exception e) {
            log.error("Failed to store emotion embedding for label {}: {}", label.getId(), e.getMessage(), e);
        }
    }

    @Transactional
    public void storeTopicEmbedding(UserTopicLabel label) {
        if (topicEmbeddingRepository.existsByLabelId(label.getId())) {
            return;
        }
        float[] embedding = embeddingModel.embed(label.getDisplayLabel());
        jdbc.update(
                "INSERT INTO topic_label_embeddings (label_id, user_id, embedding) VALUES (?, ?, ?::vector)",
                label.getId(),
                label.getUser().getId(),
                formatVector(embedding)
        );
    }

    @Transactional
    public void storeEmotionEmbedding(UserEmotionLabel label) {
        if (emotionEmbeddingRepository.existsByLabelId(label.getId())) {
            return;
        }
        float[] embedding = embeddingModel.embed(label.getDisplayLabel());
        jdbc.update(
                "INSERT INTO emotion_label_embeddings (label_id, user_id, embedding) VALUES (?, ?, ?::vector)",
                label.getId(),
                label.getUser().getId(),
                formatVector(embedding)
        );
    }

    public void deleteTopicEmbedding(UUID labelId) {
        topicEmbeddingRepository.deleteByLabelId(labelId);
    }

    public void deleteEmotionEmbedding(UUID labelId) {
        emotionEmbeddingRepository.deleteByLabelId(labelId);
    }

    private String formatVector(float[] vector) {
        if (vector == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}