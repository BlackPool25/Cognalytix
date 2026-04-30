package com.cognalytix.source.dto.journal;

import java.util.List;
import java.util.UUID;

public record MoodAnalysisPayload(
        String moodLabel,
        UUID aggregateEmotionLabelId,
        int intensity,
        String insight,
        String copingTip,
        List<String> themes) {}
