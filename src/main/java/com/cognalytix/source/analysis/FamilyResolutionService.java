package com.cognalytix.source.analysis;

import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserEmotionLabelRepository;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import com.cognalytix.source.domain.journal.UserTopicLabelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class FamilyResolutionService {

    private static final Logger log = LoggerFactory.getLogger(FamilyResolutionService.class);
    private static final int MAX_RETRIES = 2;

    private final ChatClient chatClient;
    private final UserTopicLabelRepository topicLabelRepository;
    private final UserEmotionLabelRepository emotionLabelRepository;
    private final boolean analysisEnabled;

    public FamilyResolutionService(
            ChatClient.Builder chatClientBuilder,
            UserTopicLabelRepository topicLabelRepository,
            UserEmotionLabelRepository emotionLabelRepository,
            @Value("${app.analysis.enabled:true}") boolean analysisEnabled) {
        this.chatClient = chatClientBuilder.build();
        this.topicLabelRepository = topicLabelRepository;
        this.emotionLabelRepository = emotionLabelRepository;
        this.analysisEnabled = analysisEnabled;
    }

    public void assignFamilyForNewTopic(UserTopicLabel saved) {
        if (!analysisEnabled) {
            return;
        }
        UUID id = saved.getId();
        topicLabelRepository
                .findById(id)
                .ifPresent(
                        loaded -> {
                            String key = resolveWithLlmOrFallback(
                                    loaded.getDisplayLabel(),
                                    "topic",
                                    loaded.getNormalizedKey(),
                                    loaded.getUser().getId()
                            );
                            loaded.setFamilyKey(key);
                            topicLabelRepository.save(loaded);
                        });
    }

    public void assignFamilyForNewEmotion(UserEmotionLabel saved) {
        if (!analysisEnabled) {
            return;
        }
        UUID id = saved.getId();
        emotionLabelRepository
                .findById(id)
                .ifPresent(
                        loaded -> {
                            String key = resolveWithLlmOrFallback(
                                    loaded.getDisplayLabel(),
                                    "emotion",
                                    loaded.getNormalizedKey(),
                                    loaded.getUser().getId()
                            );
                            loaded.setFamilyKey(key);
                            emotionLabelRepository.save(loaded);
                        });
    }

    private String resolveWithLlmOrFallback(String labelText, String axis, String normalizedFallback, UUID userId) {
        String sanitizedLabel = sanitizeLabel(labelText);
        List<String> existingFamilies = getExistingFamilyKeys(axis, userId);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String system = buildSystemPrompt(axis, existingFamilies);
                LlmFamilyKeyResponse r = chatClient
                        .prompt()
                        .system(system)
                        .user("Label: " + sanitizedLabel)
                        .call()
                        .entity(LlmFamilyKeyResponse.class);

                if (r != null && r.familyKey() != null) {
                    String sanitized = sanitizeFamilyKey(r.familyKey());
                    if (isValidFamilyKey(sanitized)) {
                        return sanitized;
                    }
                }
            } catch (Exception e) {
                log.debug("Family LLM attempt {} failed for {} label '{}': {}", attempt, axis, labelText, e.getMessage());
            }

            if (attempt == MAX_RETRIES) {
                return deriveFallbackFamily(sanitizedLabel);
            }
        }

        return deriveFallbackFamily(sanitizedLabel);
    }

    private String buildSystemPrompt(String axis, List<String> existingFamilies) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a label classifier. Assign ONE stable semantic cluster slug for a journaling label.\n");
        sb.append("Return ONLY this JSON — no explanation, no markdown, no extra text:\n");
        sb.append("{\"familyKey\":\"<slug>\"}\n\n");
        sb.append("RULES:\n");
        sb.append("- lowercase letters, digits, and underscores only\n");
        sb.append("- 3–60 characters, no spaces, no quotes in slug\n");
        sb.append("- The slug groups this label with future paraphrases (e.g., \"job pressure\" and \"work stress\" → work_stress)\n");
        sb.append("- Axis: \"").append(axis).append("\" — topic = life area/subject (e.g., work_project_stress); ");
        sb.append("emotion = felt tone (e.g., frustration_mild-melancholy)\n");
        sb.append("- Create a new family ONLY if no existing family closely matches\n");
        sb.append("- Keep family names short and reusable (e.g., work_stress, social_joy, health_anxiety)\n");

        if (!existingFamilies.isEmpty()) {
            sb.append("- Existing families for this user: ");
            sb.append(String.join(", ", existingFamilies)).append("\n");
        }

        return sb.toString();
    }

    private List<String> getExistingFamilyKeys(String axis, UUID userId) {
        if ("topic".equals(axis)) {
            return topicLabelRepository.findDistinctFamilyKeysByUserId(userId);
        }
        return emotionLabelRepository.findDistinctFamilyKeysByUserId(userId);
    }

    private static String sanitizeLabel(String label) {
        if (label == null) return "";
        return label.trim();
    }

    private static String sanitizeFamilyKey(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        s = s.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return s;
    }

    private static boolean isValidFamilyKey(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        if (s.length() < 3 || s.length() > 60) {
            return false;
        }
        if (!s.matches("[a-z0-9_]+")) {
            return false;
        }
        return true;
    }

    private static String deriveFallbackFamily(String labelText) {
        String[] parts = labelText.split("_");
        if (parts.length >= 2) {
            return (parts[0] + "_" + parts[1]).toLowerCase();
        }
        return labelText.replaceAll("[^a-z0-9]", "_")
                        .replaceAll("_+", "_")
                        .replaceAll("^_|_$", "")
                        .toLowerCase()
                        .substring(0, Math.min(labelText.length(), 50));
    }
}