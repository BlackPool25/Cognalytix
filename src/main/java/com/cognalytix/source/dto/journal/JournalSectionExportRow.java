package com.cognalytix.source.dto.journal;

import java.time.Instant;
import java.util.UUID;

/**
 * Flat row for tools (e.g. Power BI) — one row per topic–emotion block.
 */
public record JournalSectionExportRow(
        UUID sectionId,
        UUID entryId,
        String entryTitle,
        Instant entryCreatedAt,
        int sortOrder,
        UUID topicLabelId,
        String topicLabel,
        UUID emotionLabelId,
        String emotionLabel,
        int intensity,
        String sectionContent) {}
