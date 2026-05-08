package com.cognalytix.source.service;

import com.cognalytix.source.domain.journal.AnalysisStatus;
import com.cognalytix.source.domain.journal.GrowthInsight;
import com.cognalytix.source.domain.journal.GrowthInsightRepository;
import com.cognalytix.source.domain.journal.GrowthInsightType;
import com.cognalytix.source.domain.journal.JournalEntry;
import com.cognalytix.source.domain.journal.JournalEntryRepository;
import com.cognalytix.source.domain.journal.JournalEntrySection;
import com.cognalytix.source.domain.journal.JournalEntrySectionRepository;
import com.cognalytix.source.domain.journal.MoodAnalysis;
import com.cognalytix.source.domain.journal.MoodAnalysisRepository;
import com.cognalytix.source.dto.insights.GrowthDayMirrorPayload;
import com.cognalytix.source.dto.insights.GrowthMirrorCardPayload;
import com.cognalytix.source.dto.insights.GrowthMirrorResponse;
import com.cognalytix.source.dto.insights.GrowthSectionMirrorPayload;
import com.cognalytix.source.dto.insights.GrowthTrajectoryMirrorPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class GrowthInsightService {

    private final JournalEntryRepository journalEntryRepository;
    private final MoodAnalysisRepository moodAnalysisRepository;
    private final JournalEntrySectionRepository journalEntrySectionRepository;
    private final GrowthInsightRepository growthInsightRepository;
    private final ObjectMapper objectMapper;

    public GrowthInsightService(
            JournalEntryRepository journalEntryRepository,
            MoodAnalysisRepository moodAnalysisRepository,
            JournalEntrySectionRepository journalEntrySectionRepository,
            GrowthInsightRepository growthInsightRepository,
            ObjectMapper objectMapper) {
        this.journalEntryRepository = journalEntryRepository;
        this.moodAnalysisRepository = moodAnalysisRepository;
        this.journalEntrySectionRepository = journalEntrySectionRepository;
        this.growthInsightRepository = growthInsightRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public GrowthMirrorResponse getPostEntryMirror(UUID userId, UUID entryId) {
        JournalEntry entry =
                journalEntryRepository
                        .findByIdAndUserIdAndDeletedAtIsNull(entryId, userId)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Journal entry not found"));
        AnalysisStatus st = entry.getAnalysisStatus();
        boolean mirrorReady = st == AnalysisStatus.DONE;
        if (!mirrorReady) {
            return new GrowthMirrorResponse(
                    entryId, st.name(), false, false, null, null, null);
        }
        MoodAnalysis mood = moodAnalysisRepository.findByEntryId(entryId).orElse(null);
        if (mood == null) {
            return new GrowthMirrorResponse(entryId, st.name(), true, false, null, null, null);
        }
        List<JournalEntrySection> sections =
                journalEntrySectionRepository.findByJournalEntryIdOrderBySortOrderAsc(entryId);
        GrowthDayMirrorPayload day = toDayPayload(mood, sections);

        Optional<GrowthInsight> growthOpt =
                growthInsightRepository.findFirstByUser_IdAndTriggerEntry_IdAndInsightTypeOrderByCreatedAtDesc(
                        userId, entryId, GrowthInsightType.POST_ENTRY);

        GrowthTrajectoryMirrorPayload trajectory = null;
        boolean hasTrajectory = false;
        if (growthOpt.isPresent()) {
            GrowthInsight g = growthOpt.get();
            Map<String, Object> pd = g.getPatternData();
            if (pd != null && pd.get("mirrorCard") instanceof Map<?, ?>) {
                GrowthMirrorCardPayload card =
                        objectMapper.convertValue(pd.get("mirrorCard"), GrowthMirrorCardPayload.class);
                Map<String, Object> facts = Map.of();
                Object tf = pd.get("trajectoryFacts");
                if (tf != null) {
                    facts = objectMapper.convertValue(tf, new TypeReference<>() {});
                }
                String kind =
                        pd.get("kind") != null
                                ? String.valueOf(pd.get("kind"))
                                : "EMOTION_DRIFT_ON_TOPIC_FAMILY";
                trajectory = new GrowthTrajectoryMirrorPayload(kind, card, facts);
                hasTrajectory = true;
            }
        }
        return new GrowthMirrorResponse(entryId, st.name(), true, hasTrajectory, day, trajectory,
                growthOpt.map(GrowthInsight::getPatternType).map(Enum::name).orElse(null));
    }

    private static GrowthDayMirrorPayload toDayPayload(MoodAnalysis m, List<JournalEntrySection> sections) {
        List<String> themes = m.getThemes() != null ? m.getThemes() : List.of();
        List<GrowthSectionMirrorPayload> secDtos = new ArrayList<>();
        for (JournalEntrySection s : sections) {
            secDtos.add(
                    new GrowthSectionMirrorPayload(
                            s.getTopicLabel().getLabel(),
                            s.getTopicLabel().getFamilyKey(),
                            s.getEmotionLabel().getLabel(),
                            s.getEmotionLabel().getFamilyKey(),
                            (int) s.getIntensity(),
                            s.getContent()));
        }
        return new GrowthDayMirrorPayload(
                m.getInsight(),
                (int) m.getIntensity(),
                m.getMoodLabel(),
                themes,
                m.getCopingTip(),
                secDtos);
    }
}
