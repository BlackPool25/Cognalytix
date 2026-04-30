package com.cognalytix.source.analysis;

import java.util.UUID;

/** Fired from {@link com.cognalytix.source.service.JournalService} after a journal row is ready for LLM. */
public record JournalEntryAnalysisEvent(UUID entryId) {}
