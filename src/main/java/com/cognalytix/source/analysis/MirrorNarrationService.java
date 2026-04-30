package com.cognalytix.source.analysis;

import com.cognalytix.source.domain.journal.GrowthDirection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Turns aggregate pattern JSON into a structured mirror card (never raw journal prose). */
@Service
public class MirrorNarrationService {

    private static final Logger log = LoggerFactory.getLogger(MirrorNarrationService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public MirrorNarrationService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public LlmMirrorCardStructured narrate(EmotionDriftFacts drift, Map<String, Object> daySnapshot) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("trajectoryFacts", objectMapper.convertValue(drift, new TypeReference<>() {}));
            payload.put("daySnapshot", daySnapshot);
            payload.put("expectedDirection", drift.direction().name());
            String userJson = objectMapper.writeValueAsString(payload);
            LlmMirrorCardStructured r =
                    chatClient
                            .prompt()
                            .system(MirrorNarrationPrompts.systemPrompt())
                            .user(userJson)
                            .call()
                            .entity(LlmMirrorCardStructured.class);
            if (valid(r)) {
                return normalizeDirection(r, drift.direction());
            }
        } catch (Exception e) {
            log.info("Mirror narration LLM failed: {}", e.getMessage());
        }
        return fallbackStructured(drift, daySnapshot);
    }

    private static boolean valid(LlmMirrorCardStructured r) {
        return r != null
                && nonBlank(r.headline())
                && nonBlank(r.trajectoryLine())
                && nonBlank(r.dayAnchorLine())
                && nonBlank(r.integratedBody());
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static LlmMirrorCardStructured normalizeDirection(LlmMirrorCardStructured r, GrowthDirection expected) {
        String d = r.direction() == null ? "" : r.direction().trim().toUpperCase(Locale.ROOT);
        if (!d.equals("GROWTH") && !d.equals("REGRESSION") && !d.equals("STABLE")) {
            d = expected.name();
        }
        return new LlmMirrorCardStructured(
                r.headline().trim(),
                r.trajectoryLine().trim(),
                r.dayAnchorLine().trim(),
                r.integratedBody().trim(),
                d);
    }

    private static LlmMirrorCardStructured fallbackStructured(
            EmotionDriftFacts drift, Map<String, Object> daySnapshot) {
        String topic = drift.topicDisplayLabel();
        String headline = "Something shifted around " + topic;
        String traj =
                "Earlier stretches in this area mapped more to \""
                        + drift.priorDominantEmotionFamily()
                        + "\" (around intensity "
                        + format1(drift.priorDominantAvgIntensity())
                        + "); today centers on \""
                        + drift.currentDominantEmotionFamily()
                        + "\" (around "
                        + format1(drift.currentDominantAvgIntensity())
                        + ").";
        String dominant = String.valueOf(daySnapshot.getOrDefault("dominantMoodLabel", ""));
        String inten = String.valueOf(daySnapshot.getOrDefault("overallIntensity", ""));
        String dayAnchor =
                "Today's entry leans toward "
                        + dominant
                        + " overall (intensity "
                        + inten
                        + ") — that's the texture you're carrying right now.";
        String summary = String.valueOf(daySnapshot.getOrDefault("summaryInsight", "")).trim();
        String integrated =
                summary.isEmpty()
                        ? traj + " " + dayAnchor
                        : summary + " " + dayAnchor;
        return new LlmMirrorCardStructured(
                headline,
                traj,
                dayAnchor,
                integrated,
                drift.direction().name());
    }

    private static String format1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
