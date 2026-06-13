package com.cognalytix.source.analysis;

import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserEmotionLabelRepository;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import com.cognalytix.source.domain.journal.UserTopicLabelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

@Service
public class FamilyResolutionService {

    private static final Logger log = LoggerFactory.getLogger(FamilyResolutionService.class);

    private final UserTopicLabelRepository topicLabelRepository;
    private final UserEmotionLabelRepository emotionLabelRepository;

    private static final Map<String, String> EMOTION_TO_FAMILY = Map.ofEntries(
        Map.entry("admiration", "joy"),
        Map.entry("amusement", "joy"),
        Map.entry("anger", "anger"),
        Map.entry("annoyance", "anger"),
        Map.entry("approval", "acceptance"),
        Map.entry("caring", "joy"),
        Map.entry("confusion", "anxiety"),
        Map.entry("curiosity", "joy"),
        Map.entry("desire", "joy"),
        Map.entry("disappointment", "sadness"),
        Map.entry("disapproval", "anger"),
        Map.entry("disgust", "disgust"),
        Map.entry("embarrassment", "anxiety"),
        Map.entry("excitement", "joy"),
        Map.entry("fear", "anxiety"),
        Map.entry("gratitude", "acceptance"),
        Map.entry("grief", "sadness"),
        Map.entry("joy", "joy"),
        Map.entry("love", "joy"),
        Map.entry("nervousness", "anxiety"),
        Map.entry("optimism", "joy"),
        Map.entry("pride", "joy"),
        Map.entry("realization", "acceptance"),
        Map.entry("relief", "acceptance"),
        Map.entry("remorse", "sadness"),
        Map.entry("sadness", "sadness"),
        Map.entry("surprise", "surprise"),
        Map.entry("neutral", "neutral")
    );

    public FamilyResolutionService(
            UserTopicLabelRepository topicLabelRepository,
            UserEmotionLabelRepository emotionLabelRepository) {
        this.topicLabelRepository = topicLabelRepository;
        this.emotionLabelRepository = emotionLabelRepository;
    }

    public String resolveEmotionFamily(String rawEmotion, String normalizedKeyFallback) {
        if (rawEmotion == null || rawEmotion.isBlank()) {
            return sanitizeFamilyKey(normalizedKeyFallback);
        }
        String cleaned = rawEmotion.trim().toLowerCase(Locale.ROOT);
        String family = EMOTION_TO_FAMILY.get(cleaned);
        if (family != null) {
            return family;
        }
        return sanitizeFamilyKey(rawEmotion);
    }

    public String resolveTopicFamily(String rawTopic, String normalizedKeyFallback) {
        if (rawTopic == null || rawTopic.isBlank()) {
            return sanitizeFamilyKey(normalizedKeyFallback);
        }
        return sanitizeFamilyKey(rawTopic);
    }

    // Preserve existing methods for backward compatibility
    public void assignFamilyForNewTopic(UserTopicLabel saved) {
        if (saved.getFamilyKey() == null || saved.getFamilyKey().isBlank()) {
            saved.setFamilyKey(resolveTopicFamily(saved.getDisplayLabel(), saved.getNormalizedKey()));
            topicLabelRepository.save(saved);
        }
    }

    public void assignFamilyForNewEmotion(UserEmotionLabel saved) {
        if (saved.getFamilyKey() == null || saved.getFamilyKey().isBlank()) {
            saved.setFamilyKey(resolveEmotionFamily(saved.getDisplayLabel(), saved.getNormalizedKey()));
            emotionLabelRepository.save(saved);
        }
    }

    private static String sanitizeFamilyKey(String raw) {
        if (raw == null) {
            return "general";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        s = s.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (s.isEmpty()) {
            return "general";
        }
        return s.substring(0, Math.min(s.length(), 60));
    }
}