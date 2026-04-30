package com.cognalytix.source.analysis;

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
import com.cognalytix.source.domain.journal.UserTopicLabel;
import com.cognalytix.source.domain.journal.UserTopicLabelRepository;
import com.cognalytix.source.domain.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * After per-entry analysis succeeds: optionally detect drift, narrate structured mirror card, persist
 * {@link GrowthInsightType#POST_ENTRY}.
 */
@Service
public class PostEntryMirrorService {

    private static final Logger log = LoggerFactory.getLogger(PostEntryMirrorService.class);

    private final TransactionTemplate tx;
    private final JournalEntryRepository journalEntryRepository;
    private final MoodAnalysisRepository moodAnalysisRepository;
    private final JournalEntrySectionRepository journalEntrySectionRepository;
    private final GrowthInsightRepository growthInsightRepository;
    private final UserRepository userRepository;
    private final UserTopicLabelRepository userTopicLabelRepository;
    private final PatternAnalysisService patternAnalysisService;
    private final MirrorNarrationService mirrorNarrationService;
    private final ObjectMapper objectMapper;
    private final boolean analysisEnabled;

    public PostEntryMirrorService(
            PlatformTransactionManager platformTransactionManager,
            JournalEntryRepository journalEntryRepository,
            MoodAnalysisRepository moodAnalysisRepository,
            JournalEntrySectionRepository journalEntrySectionRepository,
            GrowthInsightRepository growthInsightRepository,
            UserRepository userRepository,
            UserTopicLabelRepository userTopicLabelRepository,
            PatternAnalysisService patternAnalysisService,
            MirrorNarrationService mirrorNarrationService,
            ObjectMapper objectMapper,
            @Value("${app.analysis.enabled:true}") boolean analysisEnabled) {
        this.tx = new TransactionTemplate(platformTransactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.journalEntryRepository = journalEntryRepository;
        this.moodAnalysisRepository = moodAnalysisRepository;
        this.journalEntrySectionRepository = journalEntrySectionRepository;
        this.growthInsightRepository = growthInsightRepository;
        this.userRepository = userRepository;
        this.userTopicLabelRepository = userTopicLabelRepository;
        this.patternAnalysisService = patternAnalysisService;
        this.mirrorNarrationService = mirrorNarrationService;
        this.objectMapper = objectMapper;
        this.analysisEnabled = analysisEnabled;
    }

    public void runAfterSuccessfulAnalysis(UUID entryId) {
        if (!analysisEnabled) {
            return;
        }
        try {
            tx.executeWithoutResult(__ -> runInTransaction(entryId));
        } catch (Exception e) {
            log.warn("Post-entry mirror pipeline failed for {}: {}", entryId, e.getMessage());
        }
    }

    private void runInTransaction(UUID entryId) {
        JournalEntry entry = journalEntryRepository.findById(entryId).orElse(null);
        if (entry == null || entry.getDeletedAt() != null) {
            return;
        }
        if (entry.getAnalysisStatus() != AnalysisStatus.DONE) {
            return;
        }
        UUID userId = entry.getUser().getId();
        growthInsightRepository.deleteByTriggerEntryIdAndInsightType(entryId, GrowthInsightType.POST_ENTRY);

        int totalJournals = (int) journalEntryRepository.countByUser_IdAndDeletedAtIsNull(userId);
        MoodAnalysis mood = moodAnalysisRepository.findByEntryId(entryId).orElse(null);
        if (mood == null) {
            return;
        }
        List<JournalEntrySection> sections =
                journalEntrySectionRepository.findByJournalEntryIdOrderBySortOrderAsc(entryId);
        Map<String, Object> daySnapshot = buildDaySnapshot(mood, sections);

        Optional<EmotionDriftFacts> driftOpt =
                patternAnalysisService.findPostEntryEmotionDrift(
                        userId, entryId, entry.getCreatedAt(), totalJournals);
        if (driftOpt.isEmpty()) {
            return;
        }
        EmotionDriftFacts drift = driftOpt.get();
        LlmMirrorCardStructured card = mirrorNarrationService.narrate(drift, daySnapshot);

        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("kind", "EMOTION_DRIFT_ON_TOPIC_FAMILY");
        pattern.put("mirrorCard", objectMapper.convertValue(card, new TypeReference<>() {}));
        pattern.put("trajectoryFacts", objectMapper.convertValue(drift, new TypeReference<>() {}));
        pattern.put("daySnapshot", daySnapshot);

        GrowthInsight gi = new GrowthInsight();
        gi.setUser(userRepository.getReferenceById(userId));
        gi.setInsightType(GrowthInsightType.POST_ENTRY);
        gi.setTriggerEntry(journalEntryRepository.getReferenceById(entryId));
        UserTopicLabel topicRef = userTopicLabelRepository.getReferenceById(drift.topicLabelId());
        gi.setTopicLabel(topicRef);
        gi.setTopicFamily(drift.topicFamilyKey());
        gi.setEmotionFamily(drift.currentDominantEmotionFamily());
        gi.setPatternData(pattern);
        gi.setNarration(card.integratedBody().trim());
        gi.setDirection(drift.direction());
        growthInsightRepository.save(gi);
    }

    private static Map<String, Object> buildDaySnapshot(MoodAnalysis m, List<JournalEntrySection> sections) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dominantMoodLabel", m.getMoodLabel());
        map.put("overallIntensity", (int) m.getIntensity());
        map.put("summaryInsight", m.getInsight());
        map.put("copingTip", m.getCopingTip());
        map.put("themes", m.getThemes() != null ? m.getThemes() : List.of());
        List<Map<String, Object>> secList = new ArrayList<>();
        for (JournalEntrySection s : sections) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topicLabel", s.getTopicLabel().getLabel());
            row.put("topicFamilyKey", s.getTopicLabel().getFamilyKey());
            row.put("emotionLabel", s.getEmotionLabel().getLabel());
            row.put("emotionFamilyKey", s.getEmotionLabel().getFamilyKey());
            row.put("intensity", (int) s.getIntensity());
            row.put("excerpt", s.getContent());
            secList.add(row);
        }
        map.put("sections", secList);
        return map;
    }
}
