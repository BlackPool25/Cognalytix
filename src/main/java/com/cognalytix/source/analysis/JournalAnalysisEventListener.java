package com.cognalytix.source.analysis;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Triggers Ollama analysis <strong>after</strong> the database transaction that created/updated a journal
 * has committed, so the async job always sees a consistent row.
 */
@Component
public class JournalAnalysisEventListener {

    private final JournalAnalysisService journalAnalysisService;

    public JournalAnalysisEventListener(JournalAnalysisService journalAnalysisService) {
        this.journalAnalysisService = journalAnalysisService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEntryReadyForAnalysis(JournalEntryAnalysisEvent event) {
        journalAnalysisService.runAnalysisAsync(event.entryId());
    }
}
