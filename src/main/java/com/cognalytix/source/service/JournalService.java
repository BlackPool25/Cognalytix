package com.cognalytix.source.service;

import com.cognalytix.source.analysis.JournalEntryAnalysisEvent;
import com.cognalytix.source.domain.journal.AnalysisStatus;
import com.cognalytix.source.domain.journal.GrowthInsightRepository;
import com.cognalytix.source.domain.journal.GrowthInsightType;
import com.cognalytix.source.domain.journal.JournalEntry;
import com.cognalytix.source.domain.journal.JournalEntryRepository;
import com.cognalytix.source.domain.journal.JournalEntrySection;
import com.cognalytix.source.domain.journal.JournalEntrySectionRepository;
import com.cognalytix.source.domain.journal.MoodAnalysis;
import com.cognalytix.source.domain.journal.MoodAnalysisRepository;
import com.cognalytix.source.domain.user.User;
import com.cognalytix.source.domain.user.UserRepository;
import com.cognalytix.source.dto.journal.JournalAnalysisStatePayload;
import com.cognalytix.source.dto.journal.JournalResponse;
import com.cognalytix.source.dto.journal.JournalSectionPayload;
import com.cognalytix.source.dto.journal.JournalWriteRequest;
import com.cognalytix.source.dto.journal.MoodAnalysisPayload;
import com.cognalytix.source.dto.user.UserLabelRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JournalService {

    private final JournalEntryRepository journalEntryRepository;
    private final MoodAnalysisRepository moodAnalysisRepository;
    private final JournalEntrySectionRepository journalEntrySectionRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final GrowthInsightRepository growthInsightRepository;
    private final boolean analysisEnabled;

    public JournalService(
            JournalEntryRepository journalEntryRepository,
            MoodAnalysisRepository moodAnalysisRepository,
            JournalEntrySectionRepository journalEntrySectionRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher,
            GrowthInsightRepository growthInsightRepository,
            @Value("${app.analysis.enabled:true}") boolean analysisEnabled) {
        this.journalEntryRepository = journalEntryRepository;
        this.moodAnalysisRepository = moodAnalysisRepository;
        this.journalEntrySectionRepository = journalEntrySectionRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.growthInsightRepository = growthInsightRepository;
        this.analysisEnabled = analysisEnabled;
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
        if (analysisEnabled) {
            eventPublisher.publishEvent(new JournalEntryAnalysisEvent(entry.getId()));
        }
        return toResponse(entry, null, List.of());
    }

    @Transactional(readOnly = true)
    public Page<JournalResponse> list(UUID userId, Pageable pageable) {
        return journalEntryRepository
                .findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                .map(e -> toResponse(e, loadMoodForList(e), List.of()));
    }

    /** List view: omit full mood payload to keep payloads small unless analysis exists and is DONE. */
    private MoodAnalysisPayload loadMoodForList(JournalEntry entry) {
        if (entry.getAnalysisStatus() != AnalysisStatus.DONE) {
            return null;
        }
        Optional<MoodAnalysis> o = moodAnalysisRepository.findByEntryId(entry.getId());
        if (o.isEmpty()) {
            return null;
        }
        return toPayload(o.get());
    }

    @Transactional(readOnly = true)
    public JournalResponse get(UUID id, UUID userId) {
        JournalEntry entry = journalEntryRepository
                .findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal entry not found"));
        Optional<MoodAnalysis> moodEntity = moodAnalysisRepository.findByEntryId(id);
        MoodAnalysisPayload mood = moodEntity.isEmpty() ? null : toPayload(moodEntity.get());
        List<JournalSectionPayload> sections = mapSections(journalEntrySectionRepository.findByJournalEntryIdOrderBySortOrderAsc(id));
        return toResponse(entry, mood, sections);
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
        growthInsightRepository.deleteByTriggerEntryIdAndInsightType(entry.getId(), GrowthInsightType.POST_ENTRY);
        moodAnalysisRepository.findByEntryId(entry.getId()).ifPresent(moodAnalysisRepository::delete);
        journalEntrySectionRepository.deleteByJournalEntryId(entry.getId());
        journalEntryRepository.save(entry);
        if (analysisEnabled) {
            eventPublisher.publishEvent(new JournalEntryAnalysisEvent(entry.getId()));
        }
        return toResponse(entry, null, List.of());
    }

    @Transactional
    public JournalResponse reanalyze(UUID id, UUID userId) {
        JournalEntry entry = journalEntryRepository
                .findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal entry not found"));
        growthInsightRepository.deleteByTriggerEntryIdAndInsightType(entry.getId(), GrowthInsightType.POST_ENTRY);
        moodAnalysisRepository.findByEntryId(entry.getId()).ifPresent(moodAnalysisRepository::delete);
        journalEntrySectionRepository.deleteByJournalEntryId(entry.getId());
        entry.setAnalysisStatus(AnalysisStatus.PENDING);
        entry.setAnalysisInProgress(false);
        entry.setLastAnalysisError(null);
        journalEntryRepository.save(entry);
        if (analysisEnabled) {
            eventPublisher.publishEvent(new JournalEntryAnalysisEvent(entry.getId()));
        }
        return toResponse(entry, null, List.of());
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

    private JournalResponse toResponse(JournalEntry entry, MoodAnalysisPayload mood, List<JournalSectionPayload> sections) {
        return new JournalResponse(
                entry.getId(),
                entry.getTitle(),
                entry.getContent(),
                entry.getWordCount(),
                entry.getAnalysisStatus().name(),
                toAnalysisState(entry),
                sections,
                mood,
                entry.getDeletedAt(),
                entry.getCreatedAt(),
                entry.getUpdatedAt());
    }

    private static JournalAnalysisStatePayload toAnalysisState(JournalEntry entry) {
        return new JournalAnalysisStatePayload(
                entry.getAnalysisAttemptCount(),
                entry.getAnalysisFailCount(),
                entry.isAnalysisInProgress(),
                entry.getLastAnalysisError());
    }

    private List<JournalSectionPayload> mapSections(List<JournalEntrySection> list) {
        return list.stream()
                .map(s -> new JournalSectionPayload(
                        s.getId(),
                        s.getSortOrder(),
                        new UserLabelRef(s.getTopicLabel().getId(), s.getTopicLabel().getLabel()),
                        new UserLabelRef(s.getEmotionLabel().getId(), s.getEmotionLabel().getLabel()),
                        s.getContent(),
                        s.getIntensity()))
                .toList();
    }

    private MoodAnalysisPayload toPayload(MoodAnalysis m) {
        List<String> themes = m.getThemes() != null ? m.getThemes() : List.of();
        return new MoodAnalysisPayload(
                m.getMoodLabel(),
                m.getAggregateEmotionLabel() != null ? m.getAggregateEmotionLabel().getId() : null,
                (int) m.getIntensity(),
                m.getInsight(),
                m.getCopingTip(),
                themes);
    }

    private static int countWords(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.trim().split("\\s+").length;
    }
}
