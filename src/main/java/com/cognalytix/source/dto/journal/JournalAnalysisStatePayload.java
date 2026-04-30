package com.cognalytix.source.dto.journal;

/** Telemetry for async analysis; safe codes only — never raw stack traces. */
public record JournalAnalysisStatePayload(
        int attemptCount, int failCount, boolean inProgress, String lastErrorCode) {}
