package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.JournalEntry;
import com.cognalytix.source.domain.journal.JournalEntrySection;
import com.cognalytix.source.domain.journal.JournalEntrySectionRepository;
import com.cognalytix.source.dto.journal.JournalSectionExportRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class JournalSectionExportService {

    private static final Instant FAR_FUTURE = Instant.parse("3000-01-01T00:00:00Z");

    private final JournalEntrySectionRepository journalEntrySectionRepository;

    public JournalSectionExportService(JournalEntrySectionRepository journalEntrySectionRepository) {
        this.journalEntrySectionRepository = journalEntrySectionRepository;
    }

    /**
     * Paged, flat topic–emotion section rows for the current user, for tools (e.g. Power BI).
     * <p>{@code toExclusive} is exclusive: rows use {@code entry.createdAt &lt; toExclusive}.</p>
     */
    @Transactional(readOnly = true)
    public Page<JournalSectionExportRow> exportSections(
            UUID userId, Instant fromInclusive, Instant toExclusive, Pageable pageable) {
        Objects.requireNonNull(userId, "userId");
        Instant from = fromInclusive != null ? fromInclusive : Instant.EPOCH;
        Instant to = toExclusive != null ? toExclusive : FAR_FUTURE;
        if (!to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to (exclusive) must be after from");
        }
        return journalEntrySectionRepository
                .findByUserIdAndEntryCreatedAtRange(userId, from, to, pageable)
                .map(this::toRow);
    }

    private JournalSectionExportRow toRow(JournalEntrySection s) {
        JournalEntry e = s.getJournalEntry();
        return new JournalSectionExportRow(
                s.getId(),
                e.getId(),
                e.getTitle(),
                e.getCreatedAt(),
                s.getSortOrder(),
                s.getTopicLabel().getId(),
                s.getTopicLabel().getLabel(),
                s.getEmotionLabel().getId(),
                s.getEmotionLabel().getLabel(),
                s.getIntensity(),
                s.getContent());
    }
}
