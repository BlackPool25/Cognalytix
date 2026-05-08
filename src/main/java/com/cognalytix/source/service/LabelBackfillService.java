package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.EmotionLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.LabelBackfillStatus;
import com.cognalytix.source.domain.journal.LabelBackfillStatusRepository;
import com.cognalytix.source.domain.journal.TopicLabelEmbeddingRepository;
import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserEmotionLabelRepository;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import com.cognalytix.source.domain.journal.UserTopicLabelRepository;
import com.cognalytix.source.domain.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LabelBackfillService {

    private static final Logger log = LoggerFactory.getLogger(LabelBackfillService.class);
    private static final int MAX_LABELS_PER_USER = 50;

    private final UserRepository userRepository;
    private final UserTopicLabelRepository topicLabelRepository;
    private final UserEmotionLabelRepository emotionLabelRepository;
    private final LabelBackfillStatusRepository backfillStatusRepository;
    private final TopicLabelEmbeddingRepository topicEmbeddingRepository;
    private final EmotionLabelEmbeddingRepository emotionEmbeddingRepository;
    private final EmbeddingStorageService embeddingStorageService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public LabelBackfillService(
            UserRepository userRepository,
            UserTopicLabelRepository topicLabelRepository,
            UserEmotionLabelRepository emotionLabelRepository,
            LabelBackfillStatusRepository backfillStatusRepository,
            TopicLabelEmbeddingRepository topicEmbeddingRepository,
            EmotionLabelEmbeddingRepository emotionEmbeddingRepository,
            EmbeddingStorageService embeddingStorageService,
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.topicLabelRepository = topicLabelRepository;
        this.emotionLabelRepository = emotionLabelRepository;
        this.backfillStatusRepository = backfillStatusRepository;
        this.topicEmbeddingRepository = topicEmbeddingRepository;
        this.emotionEmbeddingRepository = emotionEmbeddingRepository;
        this.embeddingStorageService = embeddingStorageService;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void runScheduledBackfill() {
        List<LabelBackfillStatus> pending = backfillStatusRepository.findAll().stream()
                .filter(s -> s.getCompletedAt() == null)
                .toList();

        for (LabelBackfillStatus status : pending) {
            try {
                if ("TOPIC".equals(status.getLabelType())) {
                    backfillTopicsForAllUsers();
                } else if ("EMOTION".equals(status.getLabelType())) {
                    backfillEmotionsForAllUsers();
                }
                status.setCompletedAt(Instant.now());
            } catch (Exception e) {
                log.error("Backfill failed for type {}: {}", status.getLabelType(), e.getMessage());
                status.setErrorMessage(e.getMessage());
            }
            backfillStatusRepository.save(status);
        }
    }

    public void triggerBackfill(String labelType) {
        LabelBackfillStatus status = backfillStatusRepository.findByLabelType(labelType)
                .orElseGet(() -> {
                    LabelBackfillStatus s = new LabelBackfillStatus();
                    s.setLabelType(labelType);
                    s.setStartedAt(Instant.now());
                    int total = "TOPIC".equals(labelType)
                            ? topicLabelRepository.findAll().size()
                            : emotionLabelRepository.findAll().size();
                    s.setTotalLabels(total);
                    return backfillStatusRepository.save(s);
                });

        try {
            if ("TOPIC".equals(labelType)) {
                backfillTopicsForAllUsers();
            } else if ("EMOTION".equals(labelType)) {
                backfillEmotionsForAllUsers();
            }
            status.setCompletedAt(Instant.now());
        } catch (Exception e) {
            status.setErrorMessage(e.getMessage());
        }
        backfillStatusRepository.save(status);
    }

    @Transactional
    public void backfillTopicsForAllUsers() {
        List<UUID> userIds = userRepository.findAll().stream()
                .map(u -> u.getId())
                .toList();

        for (UUID userId : userIds) {
            List<UserTopicLabel> labels = topicLabelRepository.findLabelsNeedingBackfill(userId);
            if (labels.isEmpty()) {
                continue;
            }

            int processed = 0;
            for (UserTopicLabel label : labels) {
                if (processed >= MAX_LABELS_PER_USER) {
                    break;
                }

                LabelHierarchy hierarchy = callLlmForHierarchy(label.getDisplayLabel(), "topic");
                if (hierarchy != null) {
                    Map<String, Object> labelData = buildLabelData(
                            label.getDisplayLabel(),
                            hierarchy.category(),
                            hierarchy.topic(),
                            hierarchy.detail()
                    );
                    label.setLabelData(labelData);
                    topicLabelRepository.save(label);
                    embeddingStorageService.storeTopicEmbedding(label);
                }
                processed++;
            }
            log.info("Backfilled {} topic labels for user {}", processed, userId);
        }
    }

    @Transactional
    public void backfillEmotionsForAllUsers() {
        List<UUID> userIds = userRepository.findAll().stream()
                .map(u -> u.getId())
                .toList();

        for (UUID userId : userIds) {
            List<UserEmotionLabel> labels = emotionLabelRepository.findLabelsNeedingBackfill(userId);
            if (labels.isEmpty()) {
                continue;
            }

            int processed = 0;
            for (UserEmotionLabel label : labels) {
                if (processed >= MAX_LABELS_PER_USER) {
                    break;
                }

                LabelHierarchy hierarchy = callLlmForHierarchy(label.getDisplayLabel(), "emotion");
                if (hierarchy != null) {
                    Map<String, Object> labelData = buildLabelData(
                            label.getDisplayLabel(),
                            hierarchy.category(),
                            hierarchy.topic(),
                            hierarchy.detail()
                    );
                    label.setLabelData(labelData);
                    emotionLabelRepository.save(label);
                    embeddingStorageService.storeEmotionEmbedding(label);
                }
                processed++;
            }
            log.info("Backfilled {} emotion labels for user {}", processed, userId);
        }
    }

    private LabelHierarchy callLlmForHierarchy(String labelText, String axis) {
        try {
            String systemPrompt = buildHierarchySystemPrompt();
            String userPrompt = buildHierarchyUserPrompt(labelText, axis);
            String response = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(String.class);

            if (response == null || response.isBlank()) {
                return null;
            }

            return parseHierarchyResponse(response, labelText);
        } catch (Exception e) {
            log.debug("LLM hierarchy backfill failed for '{}': {}", labelText, e.getMessage());
            return deriveFallbackHierarchy(labelText);
        }
    }

    private String buildHierarchySystemPrompt() {
        return "You are a label hierarchy parser for journaling labels. Return ONLY JSON — no explanation, no markdown.";
    }

    private String buildHierarchyUserPrompt(String labelText, String axis) {
        return """
                Parse this label into hierarchical levels.

                Label: %s
                Type: %s

                Return ONLY this JSON:
                {"category":"<top-level>","topic":"<second-level or null>","detail":"<third-level or null>"}

                Rules:
                - category: the broad life area (work, social, health, emotional, personal, creative, financial)
                - topic: the specific subject within that area (project, relationships, fitness, mood, hobby, etc.)
                - detail: specific aspect or nuance (deadline, friendship, exercise, low-melancholy, etc.)
                - Use underscores for multi-word labels (e.g., low_melancholy)
                - If label has only 1-2 words, set lower levels to null
                - If label can't be cleanly split, put all in category with null for others
                """.formatted(labelText, axis);
    }

    private LabelHierarchy parseHierarchyResponse(String json, String fallbackLabel) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String category = getString(parsed, "category");
            String topic = getStringOrNull(parsed, "topic");
            String detail = getStringOrNull(parsed, "detail");
            return new LabelHierarchy(category, topic, detail);
        } catch (Exception e) {
            log.debug("Failed to parse hierarchy JSON: {}", json);
            return deriveFallbackHierarchy(fallbackLabel);
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private String getStringOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || "null".equals(val) || "none".equals(String.valueOf(val).toLowerCase())) {
            return null;
        }
        return String.valueOf(val);
    }

    private LabelHierarchy deriveFallbackHierarchy(String label) {
        String[] parts = label.split("_");
        if (parts.length >= 3) {
            return new LabelHierarchy(parts[0], parts[1], parts[2]);
        } else if (parts.length == 2) {
            return new LabelHierarchy(parts[0], parts[1], null);
        } else {
            return new LabelHierarchy(label, null, null);
        }
    }

    private Map<String, Object> buildLabelData(String display, String category, String topic, String detail) {
        Map<String, Object> data = new HashMap<>();
        data.put("display", display);
        data.put("category", category);
        data.put("topic", topic);
        data.put("detail", detail);
        return data;
    }

    private record LabelHierarchy(String category, String topic, String detail) {}
}