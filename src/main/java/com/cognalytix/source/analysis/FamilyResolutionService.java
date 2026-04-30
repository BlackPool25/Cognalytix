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

import java.util.Locale;
import java.util.UUID;

/**
 * Assigns a semantic {@code family_key} once when a new label row is created (LLM); falls back to
 * {@code normalized_key} on failure.
 */
@Service
public class FamilyResolutionService {

    private static final Logger log = LoggerFactory.getLogger(FamilyResolutionService.class);

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
                            String key = resolveWithLlmOrFallback(loaded.getLabel(), "topic", loaded.getNormalizedKey());
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
                            String key = resolveWithLlmOrFallback(loaded.getLabel(), "emotion", loaded.getNormalizedKey());
                            loaded.setFamilyKey(key);
                            emotionLabelRepository.save(loaded);
                        });
    }

    private String resolveWithLlmOrFallback(String labelText, String axis, String normalizedFallback) {
        try {
            String system =
                    """
                    You assign ONE stable semantic cluster slug for a user's journaling label.
                    Return ONLY valid JSON: {"familyKey":"<slug>"}.
                    Rules: lowercase ASCII letters, digits, underscores only; 3–60 chars; no spaces; no quotes inside slug.
                    The slug groups this label with future paraphrases (e.g. "job pressure" and "work stress" → work_stress).
                    Axis is "%s" (topic = life area / subject; emotion = felt tone).
                    """.formatted(axis);
            LlmFamilyKeyResponse r =
                    chatClient
                            .prompt()
                            .system(system.strip())
                            .user("Label text: " + labelText)
                            .call()
                            .entity(LlmFamilyKeyResponse.class);
            if (r != null && r.familyKey() != null) {
                String s = sanitizeFamilyKey(r.familyKey());
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Exception e) {
            log.debug("Family LLM failed for {} label '{}': {}", axis, labelText, e.getMessage());
        }
        return truncateKey(normalizedFallback);
    }

    private static String sanitizeFamilyKey(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        s = s.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (s.isEmpty()) {
            return null;
        }
        return truncateKey(s);
    }

    private static String truncateKey(String s) {
        if (s == null) {
            return "unknown";
        }
        if (s.length() > 100) {
            return s.substring(0, 100);
        }
        return s;
    }
}
