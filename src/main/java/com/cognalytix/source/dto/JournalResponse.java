package com.cognalytix.source.dto;

import java.time.Instant;
import java.util.UUID;

public record JournalResponse(
        UUID id,
        String title,
        String content,
        int wordCount,
        String analysisStatus,
        MoodAnalysisPayload moodAnalysis,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
