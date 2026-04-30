package com.cognalytix.source.dto.insights;

import java.util.List;

public record GrowthDayMirrorPayload(
        String summaryInsight,
        int overallIntensity,
        String dominantMoodLabel,
        List<String> themes,
        String copingTip,
        List<GrowthSectionMirrorPayload> sections) {}
