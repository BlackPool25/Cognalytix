package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.AnalysisStatus;
import com.cognalytix.source.domain.journal.JournalEntry;
import com.cognalytix.source.domain.journal.JournalEntryRepository;
import com.cognalytix.source.domain.journal.MoodAnalysis;
import com.cognalytix.source.domain.journal.MoodAnalysisRepository;
import com.cognalytix.source.domain.user.User;
import com.cognalytix.source.domain.user.UserRepository;
import com.cognalytix.source.dto.JournalResponse;
import com.cognalytix.source.dto.JournalWriteRequest;
import com.cognalytix.source.dto.MoodAnalysisPayload;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class JournalService {

    private final JournalEntryRepository journalEntryRepository;
    private final MoodAnalysisRepository moodAnalysisRepository;
    private final UserRepository userRepository;

    public JournalService(
            JournalEntryRepository journalEntryRepository,
            MoodAnalysisRepository moodAnalysisRepository,
            UserRepository userRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.moodAnalysisRepository = moodAnalysisRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public JournalResponse create(JournalWriteRequest request, UUID userId) {
        User user = userRepository.getReferenceById(userId);
        JournalEntry entry = new JournalEntry();
        entry.setUser(user);
        entry.setTitle(request.title().trim());
        entry.setContent(request.content());
        entry.setWordCount(countWords(request.content()));
        entry.setAnalysisStatus(AnalysisStatus.PENDING);
        journalEntryRepository.save(entry);
        return toResponse(entry, null);
    }

    @Transactional(readOnly = true)
    public Page<JournalResponse> list(UUID userId, Pageable pageable) {
        return journalEntryRepository
                .findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                .map(e -> toResponse(e, loadMoodForList(e)));
    }

    /** List view: omit full mood payload to keep payloads small unless analysis exists and is DONE. */
    private MoodAnalysisPayload loadMoodForList(JournalEntry entry) {
        if (entry.getAnalysisStatus() != AnalysisStatus.DONE) {
            return null;
        }
        return moodAnalysisRepository.findByEntryId(entry.getId()).map(this::toPayload).orElse(null);
    }

    @Transactional(readOnly = true)
    public JournalResponse get(UUID id, UUID userId) {
        JournalEntry entry = journalEntryRepository
                .findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal entry not found"));
        MoodAnalysisPayload mood =
                moodAnalysisRepository.findByEntryId(id).map(this::toPayload).orElse(null);
        return toResponse(entry, mood);
    }

    @Transactional
    public JournalResponse update(UUID id, JournalWriteRequest request, UUID userId) {
        JournalEntry entry = journalEntryRepository
                .findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal entry not found"));
        entry.setTitle(request.title().trim());
        entry.setContent(request.content());
        entry.setWordCount(countWords(request.content()));
        entry.setAnalysisStatus(AnalysisStatus.PENDING);
        moodAnalysisRepository.findByEntryId(entry.getId()).ifPresent(moodAnalysisRepository::delete);
        journalEntryRepository.save(entry);
        return toResponse(entry, null);
    }

    @Transactional
    public void softDelete(UUID entryId, UUID userId) {
        JournalEntry entry = journalEntryRepository
                .findByIdAndUserIdAndDeletedAtIsNull(entryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal entry not found"));
        entry.setDeletedAt(Instant.now());
    }

    @Transactional
    public void permanentDelete(UUID entryId, UUID userId) {
        JournalEntry entry = journalEntryRepository
                .findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal entry not found"));
        journalEntryRepository.delete(entry);
    }

    private JournalResponse toResponse(JournalEntry entry, MoodAnalysisPayload mood) {
        return new JournalResponse(
                entry.getId(),
                entry.getTitle(),
                entry.getContent(),
                entry.getWordCount(),
                entry.getAnalysisStatus().name(),
                mood,
                entry.getDeletedAt(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }

    private MoodAnalysisPayload toPayload(MoodAnalysis m) {
        List<String> themes = m.getThemes() != null ? m.getThemes() : List.of();
        return new MoodAnalysisPayload(m.getMoodLabel(), m.getIntensity(), m.getInsight(), m.getCopingTip(), themes);
    }

    private static int countWords(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.trim().split("\\s+").length;
    }
}
