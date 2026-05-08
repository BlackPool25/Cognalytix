package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.EmotionLabelEmbedding;
import com.cognalytix.source.domain.journal.EmotionLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.TopicLabelEmbedding;
import com.cognalytix.source.domain.journal.TopicLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
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

    public EmbeddingStorageService(
            OllamaEmbeddingModel embeddingModel,
            TopicLabelEmbeddingRepository topicEmbeddingRepository,
            EmotionLabelEmbeddingRepository emotionEmbeddingRepository) {
        this.embeddingModel = embeddingModel;
        this.topicEmbeddingRepository = topicEmbeddingRepository;
        this.emotionEmbeddingRepository = emotionEmbeddingRepository;
    }

    @Async("analysisExecutor")
    public void storeTopicEmbeddingAsync(UserTopicLabel label) {
        try {
            storeTopicEmbedding(label);
        } catch (Exception e) {
            log.debug("Failed to store topic embedding for label {}: {}", label.getId(), e.getMessage());
        }
    }

    @Async("analysisExecutor")
    public void storeEmotionEmbeddingAsync(UserEmotionLabel label) {
        try {
            storeEmotionEmbedding(label);
        } catch (Exception e) {
            log.debug("Failed to store emotion embedding for label {}: {}", label.getId(), e.getMessage());
        }
    }

    @Transactional
    public void storeTopicEmbedding(UserTopicLabel label) {
        if (topicEmbeddingRepository.existsByLabelId(label.getId())) {
            return;
        }
        float[] embedding = embeddingModel.embed(label.getDisplayLabel());
        TopicLabelEmbedding entity = new TopicLabelEmbedding();
        entity.setLabel(label);
        entity.setUser(label.getUser());
        entity.setEmbedding(embedding);
        topicEmbeddingRepository.save(entity);
    }

    @Transactional
    public void storeEmotionEmbedding(UserEmotionLabel label) {
        if (emotionEmbeddingRepository.existsByLabelId(label.getId())) {
            return;
        }
        float[] embedding = embeddingModel.embed(label.getDisplayLabel());
        EmotionLabelEmbedding entity = new EmotionLabelEmbedding();
        entity.setLabel(label);
        entity.setUser(label.getUser());
        entity.setEmbedding(embedding);
        emotionEmbeddingRepository.save(entity);
    }

    public void deleteTopicEmbedding(UUID labelId) {
        topicEmbeddingRepository.deleteByLabelId(labelId);
    }

    public void deleteEmotionEmbedding(UUID labelId) {
        emotionEmbeddingRepository.deleteByLabelId(labelId);
    }
}