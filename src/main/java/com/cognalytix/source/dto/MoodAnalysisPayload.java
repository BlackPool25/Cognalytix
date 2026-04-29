package com.cognalytix.source.dto;

import java.util.List;

public record MoodAnalysisPayload(
        String moodLabel,
        int intensity,
        String insight,
        String copingTip,
        List<String> themes
) {
}
