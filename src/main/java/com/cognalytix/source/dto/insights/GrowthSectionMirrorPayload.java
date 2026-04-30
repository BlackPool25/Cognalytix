package com.cognalytix.source.dto.insights;

public record GrowthSectionMirrorPayload(
        String topicLabel,
        String topicFamilyKey,
        String emotionLabel,
        String emotionFamilyKey,
        int intensity,
        String excerpt) {}
