package com.cognalytix.source.analysis;

import com.cognalytix.source.domain.journal.AnalysisStatus;
import com.cognalytix.source.domain.journal.JournalEntry;
import com.cognalytix.source.domain.journal.JournalEntryRepository;
import com.cognalytix.source.domain.journal.JournalEntrySection;
import com.cognalytix.source.domain.journal.JournalEntrySectionRepository;
import com.cognalytix.source.domain.journal.MoodAnalysis;
import com.cognalytix.source.domain.journal.MoodAnalysisRepository;
import com.cognalytix.source.domain.journal.UserEmotionLabel;
import com.cognalytix.source.domain.journal.UserTopicLabel;
import com.cognalytix.source.service.SemanticLabelSelector;
import com.cognalytix.source.service.UserLabelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Async Ollama (Spring AI {@link ChatClient}) analysis: topic sections + aggregate mood, persisted
 * to {@code journal_entry_sections}, {@code mood_analyses}, and per-user label tables.
 *
 * <p>The model receives this user's saved topic/emotion strings so it can reuse them verbatim; persistence
 * uses {@link UserLabelService#resolveEmotionFromModel} / {@link UserLabelService#resolveTopicFromModel}
 * so normalized-key and minor casing drift still map to existing rows.</p>
 */
@Service
public class JournalAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(JournalAnalysisService.class);
    private static final int MAX_SECTIONS = 32;
    private static final int MAX_THEMES = 8;

    private final ChatClient chatClient;
    private final JournalEntryRepository journalEntryRepository;
    private final MoodAnalysisRepository moodAnalysisRepository;
    private final JournalEntrySectionRepository journalEntrySectionRepository;
    private final UserLabelService userLabelService;
    private final PostEntryMirrorService postEntryMirrorService;
    private final SemanticLabelSelector semanticLabelSelector;
    private final TransactionTemplate tx;
    private final boolean analysisEnabled;

    public JournalAnalysisService(
            ChatClient.Builder chatClientBuilder,
            JournalEntryRepository journalEntryRepository,
            MoodAnalysisRepository moodAnalysisRepository,
            JournalEntrySectionRepository journalEntrySectionRepository,
            UserLabelService userLabelService,
            PostEntryMirrorService postEntryMirrorService,
            SemanticLabelSelector semanticLabelSelector,
            PlatformTransactionManager platformTransactionManager,
            @Value("${app.analysis.enabled:true}") boolean analysisEnabled) {
        this.chatClient = chatClientBuilder.build();
        this.journalEntryRepository = journalEntryRepository;
        this.moodAnalysisRepository = moodAnalysisRepository;
        this.journalEntrySectionRepository = journalEntrySectionRepository;
        this.userLabelService = userLabelService;
        this.postEntryMirrorService = postEntryMirrorService;
        this.semanticLabelSelector = semanticLabelSelector;
        this.tx = new TransactionTemplate(platformTransactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.analysisEnabled = analysisEnabled;
    }

    @Async("analysisExecutor")
    public void runAnalysisAsync(UUID entryId) {
        if (!analysisEnabled) {
            log.debug("Analysis disabled; skipping entry {}", entryId);
            return;
        }
        try {
            runAnalysis(entryId);
        } catch (Exception e) {
            log.warn("Unexpected analysis error for entry {}: {}", entryId, e.getMessage());
            markFailed(entryId, AnalysisErrorCode.INTERNAL, null);
        }
    }

    private void runAnalysis(UUID entryId) {
        JournalEntry entry = beginOrAbort(entryId);
        if (entry == null) {
            return;
        }
        EntryContext ctx = loadEntryContext(entry.getId());
        if (ctx == null) {
            markFailed(entryId, AnalysisErrorCode.VALIDATION_FAILED, null);
            return;
        }
        LlmJournalAnalysisResult result;
        try {
            result = callLlm(ctx);
        } catch (Exception e) {
            log.info("LLM call failed for entry {}: {} ({})", entryId, e.getClass().getSimpleName(), e.getMessage());
            markFailed(entryId, mapToErrorCode(e), e.getMessage());
            return;
        }
        if (!validateResult(result, entryId)) {
            markFailed(entryId, AnalysisErrorCode.VALIDATION_FAILED, null);
            return;
        }
        try {
            saveSuccess(ctx.entryId(), ctx.userId(), result);
            postEntryMirrorService.runAfterSuccessfulAnalysis(ctx.entryId());
        } catch (Exception e) {
            log.warn("Persist failed for entry {}: {}", entryId, e.getMessage());
            markFailed(entryId, AnalysisErrorCode.PERSIST_FAILED, e.getClass().getSimpleName());
        }
    }

    private boolean validateResult(LlmJournalAnalysisResult result, UUID entryId) {
        if (result == null || result.summary() == null) {
            return false;
        }
        LlmJournalAnalysisResult.LlmEntrySummary s = result.summary();
        if (result.sections() == null || result.sections().isEmpty()) {
            log.info("LLM returned no sections for entry {}", entryId);
            return false;
        }
        if (result.sections().size() > MAX_SECTIONS) {
            return false;
        }
        for (LlmJournalAnalysisResult.LlmTopicSection sec : result.sections()) {
            if (sec == null) {
                return false;
            }
            if (sec.topic() == null
                    || sec.emotion() == null
                    || sec.content() == null
                    || sec.topic().isBlank()
                    || sec.emotion().isBlank()
                    || sec.content().isBlank()) {
                return false;
            }
            if (sec.intensity() < 1 || sec.intensity() > 5) {
                return false;
            }
        }
        if (s.dominantMood() == null
                || s.dominantMood().isBlank()
                || s.intensity() < 1
                || s.intensity() > 5) {
            return false;
        }
        if (s.insight() == null || s.insight().isBlank()) {
            return false;
        }
        return true;
    }

    private LlmJournalAnalysisResult callLlm(EntryContext ctx) {
        List<SemanticLabelSelector.LabelCandidate> topicCandidates =
                semanticLabelSelector.findRelevantTopics(ctx.userId(), ctx.content(), ctx.title());
        List<SemanticLabelSelector.LabelCandidate> emotionCandidates =
                semanticLabelSelector.findRelevantEmotions(ctx.userId(), ctx.content(), ctx.title());

        List<String> topics = topicCandidates.stream()
                .map(SemanticLabelSelector.LabelCandidate::displayText)
                .toList();
        List<String> emotions = emotionCandidates.stream()
                .map(SemanticLabelSelector.LabelCandidate::displayText)
                .toList();

        String system = AnalysisPrompts.buildSystemPrompt(topics, emotions);
        return chatClient
                .prompt()
                .system(system)
                .user(AnalysisPrompts.userPayload(ctx.title(), ctx.content()))
                .call()
                .entity(LlmJournalAnalysisResult.class);
    }

    private void saveSuccess(UUID entryId, UUID userId, LlmJournalAnalysisResult r) {
        tx.executeWithoutResult(
                __ -> {
                    JournalEntry fresh =
                            journalEntryRepository.findById(entryId).orElse(null);
                    if (fresh == null
                            || fresh.getDeletedAt() != null) {
                        return;
                    }
                    UUID uid = fresh.getUser().getId();
                    if (!uid.equals(userId)) {
                        log.warn("Journal {} user mismatch during analysis persist", entryId);
                        return;
                    }

                    moodAnalysisRepository.deleteByEntryId(fresh.getId());
                    journalEntrySectionRepository.deleteByJournalEntryId(fresh.getId());

                    LlmJournalAnalysisResult.LlmEntrySummary sum = r.summary();
                    UserEmotionLabel aggregate = userLabelService.resolveEmotionFromModel(uid, sum.dominantMood());
                    int intensity = clampIntensity(sum.intensity());
                    String coping = null;
                    if (intensity >= 4 && sum.copingTip() != null && !sum.copingTip().isBlank()) {
                        coping = trimLen(sum.copingTip(), 10_000);
                    }

                    List<String> themes = normalizeThemes(sum.themeHints());
                    MoodAnalysis m = new MoodAnalysis();
                    m.setEntryId(fresh.getId());
                    m.setUser(fresh.getUser());
                    m.setMoodLabel(aggregate.getLabel());
                    m.setAggregateEmotionLabel(aggregate);
                    m.setIntensity((short) intensity);
                    m.setInsight(trimLen(sum.insight(), 10_000));
                    m.setCopingTip(coping);
                    m.setThemes(themes);
                    moodAnalysisRepository.save(m);

                    int i = 0;
                    for (LlmJournalAnalysisResult.LlmTopicSection sec : r.sections()) {
                        UserTopicLabel tLabel = userLabelService.resolveTopicFromModel(uid, sec.topic());
                        UserEmotionLabel eLabel = userLabelService.resolveEmotionFromModel(uid, sec.emotion());
                        JournalEntrySection section = new JournalEntrySection();
                        section.setJournalEntry(fresh);
                        section.setUser(fresh.getUser());
                        section.setSortOrder(i);
                        section.setTopicLabel(tLabel);
                        section.setEmotionLabel(eLabel);
                        section.setContent(sec.content().trim());
                        section.setIntensity((short) sec.intensity());
                        journalEntrySectionRepository.save(section);
                        i++;
                    }
                    fresh.setAnalysisStatus(AnalysisStatus.DONE);
                    fresh.setAnalysisInProgress(false);
                    fresh.setLastAnalysisError(null);
                    journalEntryRepository.save(fresh);
                });
    }

    private EntryContext loadEntryContext(UUID entryId) {
        return tx.execute(
                status -> {
                    Optional<JournalEntry> opt = journalEntryRepository.findById(entryId);
                    if (opt.isEmpty()) {
                        return null;
                    }
                    JournalEntry e = opt.get();
                    if (e.getDeletedAt() != null) {
                        return null;
                    }
                    return new EntryContext(e.getId(), e.getUser().getId(), e.getTitle(), e.getContent());
                });
    }

    /**
     * Snapshot loaded in a transaction so {@code userId}, title, and content are safe to use after the
     * session closes (async worker thread).
     */
    private record EntryContext(UUID entryId, UUID userId, String title, String content) {}

    private JournalEntry beginOrAbort(UUID entryId) {
        return tx.execute(
                status -> {
                    Optional<JournalEntry> opt = journalEntryRepository.findById(entryId);
                    if (opt.isEmpty()) {
                        return null;
                    }
                    JournalEntry e = opt.get();
                    if (e.getDeletedAt() != null) {
                        return null;
                    }
                    e.setAnalysisAttemptCount(e.getAnalysisAttemptCount() + 1);
                    e.setAnalysisInProgress(true);
                    e.setLastAnalysisError(null);
                    e.setAnalysisStatus(AnalysisStatus.PENDING);
                    return journalEntryRepository.save(e);
                });
    }

    private void markFailed(
            UUID entryId, AnalysisErrorCode code, String contextSuffix) {
        String codeStr = code.code();
        if (contextSuffix != null
                && !contextSuffix.isBlank()
                && code == AnalysisErrorCode.INTERNAL) {
            String c = codeStr + ":" + trimLen(contextSuffix, 32);
            codeStr = c.length() > 64 ? c.substring(0, 64) : c;
        }
        String finalCode = codeStr;
        tx.executeWithoutResult(
                __ -> {
                    journalEntryRepository
                            .findById(entryId)
                            .ifPresent(
                                    e -> {
                                        if (e.getDeletedAt() != null) {
                                            e.setAnalysisInProgress(false);
                                            e.setLastAnalysisError(null);
                                            journalEntryRepository.save(e);
                                            return;
                                        }
                                        e.setAnalysisInProgress(false);
                                        e.setAnalysisStatus(AnalysisStatus.FAILED);
                                        e.setAnalysisFailCount(e.getAnalysisFailCount() + 1);
                                        e.setLastAnalysisError(
                                                trimLen(
                                                        finalCode.length() > 64
                                                                ? finalCode.substring(0, 64)
                                                                : finalCode,
                                                        64));
                                        journalEntryRepository.save(e);
                                    });
                });
    }

    private AnalysisErrorCode mapToErrorCode(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t != t.getCause()) {
            t = t.getCause();
        }
        if (e instanceof ResourceAccessException) {
            return AnalysisErrorCode.LLM_UNREACHABLE;
        }
        String n = t.getClass().getSimpleName().toLowerCase();
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (n.contains("ollama")
                || n.contains("http")
                || n.contains("connect")
                || msg.contains("refused")
                || msg.contains("connect")) {
            return AnalysisErrorCode.LLM_UNREACHABLE;
        }
        if (n.contains("json") || n.contains("parse") || n.contains("mapping")) {
            return AnalysisErrorCode.LLM_INVALID_RESPONSE;
        }
        if (e instanceof IllegalArgumentException) {
            return AnalysisErrorCode.LLM_INVALID_RESPONSE;
        }
        return AnalysisErrorCode.LLM_INVALID_RESPONSE;
    }

    private static int clampIntensity(int v) {
        if (v < 1) {
            return 1;
        }
        if (v > 5) {
            return 5;
        }
        return v;
    }

    private static String trimLen(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static List<String> normalizeThemes(List<String> hints) {
        if (hints == null) {
            return List.of();
        }
        return hints.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> trimLen(s, 200))
                .distinct()
                .limit(MAX_THEMES)
                .toList();
    }
}
