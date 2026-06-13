package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.EmotionLabelEmbedding;
import com.cognalytix.source.domain.journal.EmotionLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.TopicLabelEmbedding;
import com.cognalytix.source.domain.journal.TopicLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserEmotionLabelRepository;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import com.cognalytix.source.domain.journal.UserTopicLabelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SemanticLabelSelector {

    private static final Logger log = LoggerFactory.getLogger(SemanticLabelSelector.class);
    private static final int DEFAULT_TOP_K = 100;

    private final OllamaEmbeddingModel embeddingModel;
    private final TopicLabelEmbeddingRepository topicEmbeddingRepository;
    private final EmotionLabelEmbeddingRepository emotionEmbeddingRepository;
    private final UserTopicLabelRepository topicLabelRepository;
    private final UserEmotionLabelRepository emotionLabelRepository;
    private final EmbeddingStorageService embeddingStorageService;

    /**
     * In-process cache for embedding vectors. The key is the lowercase-trimmed text.
     * GoEmotions labels (28 fixed strings) and common topic keyphrases appear across
     * many entries and users; caching them eliminates redundant Ollama calls that
     * would otherwise each cost several seconds on CPU.
     *
     * This cache is intentionally unbounded and lives for the JVM lifetime — the number
     * of distinct short labels a deployment will see is bounded in practice (< 10k).
     */
    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();

    public SemanticLabelSelector(
            OllamaEmbeddingModel embeddingModel,
            TopicLabelEmbeddingRepository topicEmbeddingRepository,
            EmotionLabelEmbeddingRepository emotionEmbeddingRepository,
            UserTopicLabelRepository topicLabelRepository,
            UserEmotionLabelRepository emotionLabelRepository,
            EmbeddingStorageService embeddingStorageService) {
        this.embeddingModel = embeddingModel;
        this.topicEmbeddingRepository = topicEmbeddingRepository;
        this.emotionEmbeddingRepository = emotionEmbeddingRepository;
        this.topicLabelRepository = topicLabelRepository;
        this.emotionLabelRepository = emotionLabelRepository;
        this.embeddingStorageService = embeddingStorageService;
    }

    public List<LabelCandidate> findRelevantTopics(UUID userId, String content, String title, int topK) {
        List<UserTopicLabel> allLabels = topicLabelRepository.findAllByUser_IdOrderByLabelAsc(userId);

        if (allLabels.isEmpty()) {
            return List.of();
        }

        Map<UUID, UserTopicLabel> labelMap = new HashMap<>();
        for (UserTopicLabel label : allLabels) {
            labelMap.put(label.getId(), label);
        }

        for (UserTopicLabel label : allLabels) {
            if (!topicEmbeddingRepository.existsByLabelId(label.getId())) {
                embeddingStorageService.storeTopicEmbeddingAsync(label);
            }
        }

        try {
            float[] queryEmbedding = embedText(buildQueryText(title, content));
            List<Object[]> rows = topicEmbeddingRepository.findLabelIdAndEmbeddingByUserId(userId);

            List<ScoredLabel> scored = new ArrayList<>();
            for (Object[] row : rows) {
                UUID labelId = (UUID) row[0];
                float[] storedEmbedding = (float[]) row[1];
                double similarity = cosineSimilarity(queryEmbedding, storedEmbedding);
                UserTopicLabel label = labelMap.get(labelId);
                if (label != null) {
                    scored.add(new ScoredLabel(label, similarity));
                }
            }

            scored.sort(Comparator.comparingDouble(ScoredLabel::similarity).reversed());

            int limit = Math.min(topK, scored.size());
            List<LabelCandidate> candidates = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                UserTopicLabel label = scored.get(i).asTopicLabel();
                candidates.add(new LabelCandidate(
                        label.getId(),
                        label.getDisplayLabel(),
                        label.getFamilyKey(),
                        scored.get(i).similarity()
                ));
            }

            return candidates;
        } catch (Exception e) {
            log.warn("Semantic label selection failed for user {}: {}", userId, e.getMessage());
            return fallbackToAllLabels(allLabels, topK);
        }
    }

    public List<LabelCandidate> findRelevantEmotions(UUID userId, String content, String title, int topK) {
        List<UserEmotionLabel> allLabels = emotionLabelRepository.findAllByUser_IdOrderByLabelAsc(userId);

        if (allLabels.isEmpty()) {
            return List.of();
        }

        Map<UUID, UserEmotionLabel> labelMap = new HashMap<>();
        for (UserEmotionLabel label : allLabels) {
            labelMap.put(label.getId(), label);
        }

        for (UserEmotionLabel label : allLabels) {
            if (!emotionEmbeddingRepository.existsByLabelId(label.getId())) {
                embeddingStorageService.storeEmotionEmbeddingAsync(label);
            }
        }

        try {
            float[] queryEmbedding = embedText(buildQueryText(title, content));
            List<Object[]> rows = emotionEmbeddingRepository.findLabelIdAndEmbeddingByUserId(userId);

            List<ScoredLabel> scored = new ArrayList<>();
            for (Object[] row : rows) {
                UUID labelId = (UUID) row[0];
                float[] storedEmbedding = (float[]) row[1];
                double similarity = cosineSimilarity(queryEmbedding, storedEmbedding);
                UserEmotionLabel label = labelMap.get(labelId);
                if (label != null) {
                    scored.add(new ScoredLabel(label, similarity));
                }
            }

            scored.sort(Comparator.comparingDouble(ScoredLabel::similarity).reversed());

            int limit = Math.min(topK, scored.size());
            List<LabelCandidate> candidates = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                UserEmotionLabel label = scored.get(i).asEmotionLabel();
                candidates.add(new LabelCandidate(
                        label.getId(),
                        label.getDisplayLabel(),
                        label.getFamilyKey(),
                        scored.get(i).similarity()
                ));
            }

            return candidates;
        } catch (Exception e) {
            log.warn("Semantic emotion selection failed for user {}: {}", userId, e.getMessage());
            return fallbackToAllEmotions(allLabels, topK);
        }
    }

    public List<LabelCandidate> findRelevantTopics(UUID userId, String content, String title) {
        return findRelevantTopics(userId, content, title, DEFAULT_TOP_K);
    }

    public List<LabelCandidate> findRelevantEmotions(UUID userId, String content, String title) {
        return findRelevantEmotions(userId, content, title, DEFAULT_TOP_K);
    }

    private String buildQueryText(String title, String content) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title).append(". ");
        }
        if (content != null && !content.isBlank()) {
            sb.append(content);
        }
        return sb.toString();
    }

    private float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            text = "";
        }
        if (text.length() > 8000) {
            text = text.substring(0, 8000);
        }
        final String cacheKey = text.toLowerCase().trim();
        return embeddingCache.computeIfAbsent(cacheKey, embeddingModel::embed);
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<LabelCandidate> fallbackToAllLabels(List<UserTopicLabel> labels, int limit) {
        int size = Math.min(limit, labels.size());
        List<LabelCandidate> candidates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UserTopicLabel label = labels.get(i);
            candidates.add(new LabelCandidate(
                    label.getId(),
                    label.getDisplayLabel(),
                    label.getFamilyKey(),
                    0.0
            ));
        }
        return candidates;
    }

    private List<LabelCandidate> fallbackToAllEmotions(List<UserEmotionLabel> labels, int limit) {
        int size = Math.min(limit, labels.size());
        List<LabelCandidate> candidates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UserEmotionLabel label = labels.get(i);
            candidates.add(new LabelCandidate(
                    label.getId(),
                    label.getDisplayLabel(),
                    label.getFamilyKey(),
                    0.0
            ));
        }
        return candidates;
    }

    private record ScoredLabel(Object label, double similarity) {
        public UserTopicLabel asTopicLabel() {
            return (UserTopicLabel) label;
        }
        public UserEmotionLabel asEmotionLabel() {
            return (UserEmotionLabel) label;
        }
    }

    public record LabelCandidate(
            UUID labelId,
            String displayText,
            String familyKey,
            double relevanceScore
    ) {}
}