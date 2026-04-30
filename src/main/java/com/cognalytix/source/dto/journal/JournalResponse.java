package com.cognalytix.source.dto;

import com.cognalytix.source.dto.journal.JournalAnalysisStatePayload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JournalResponse(
        UUID id,
        String title,
        String content,
        int wordCount,
        String analysisStatus,
        JournalAnalysisStatePayload analysisState,
        List<JournalSectionPayload> sections,
        MoodAnalysisPayload moodAnalysis,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {}
