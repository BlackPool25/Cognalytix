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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Async analysis pipeline: sidecar classification → pgvector label resolution → insight generation.
 *
 * <p><b>Label resolution strategy (as of sidecar-trust refactor):</b>
 * GoEmotions produces human-readable, semantically precise labels (e.g. "admiration", "fear").
 * Sidecar MiniLM produces meaningful keyphrase topics (e.g. "project deadline").
 * These labels are trusted directly. The pipeline is:
 * <ol>
 *   <li>pgvector similarity ≥ 0.75 → reuse user's existing personal label (vocabulary continuity)</li>
 *   <li>similarity &lt; 0.75 → use the sidecar label directly, store as new user label</li>
 * </ol>
 * The old {@code personalizeLabel} LLM call is eliminated. It was calling a 14B model to rename
 * GoEmotions output, producing 30+ extra LLM calls per entry and 15-minute processing times.
 *
 * <p>The section loop is parallelised via {@code parallelStream()} since sections are independent.
 */
@Service
public class JournalAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(JournalAnalysisService.class);
    private static final int MAX_SECTIONS = 32;
    private static final int MAX_THEMES = 8;

    /**
     * Minimum useful keyphrase length. Single-character or empty topics from the sidecar
     * fall back to "general" rather than being stored as useless labels.
     */
    private static final int MIN_TOPIC_LENGTH = 2;

    private final ChatClient chatClient;
    private final JournalEntryRepository journalEntryRepository;
    private final MoodAnalysisRepository moodAnalysisRepository;
    private final JournalEntrySectionRepository journalEntrySectionRepository;
    private final UserLabelService userLabelService;
    private final PostEntryMirrorService postEntryMirrorService;
    private final SemanticLabelSelector semanticLabelSelector;
    private final SidecarClient sidecarClient;
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
            SidecarClient sidecarClient,
            PlatformTransactionManager platformTransactionManager,
            @Value("${app.analysis.enabled:true}") boolean analysisEnabled) {
        // Primary bean (chat-model / qwen2.5:3b) is auto-wired here via @Primary on OllamaConfig.
        this.chatClient = chatClientBuilder.build();
        this.journalEntryRepository = journalEntryRepository;
        this.moodAnalysisRepository = moodAnalysisRepository;
        this.journalEntrySectionRepository = journalEntrySectionRepository;
        this.userLabelService = userLabelService;
        this.postEntryMirrorService = postEntryMirrorService;
        this.semanticLabelSelector = semanticLabelSelector;
        this.sidecarClient = sidecarClient;
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

    private static final double SIMILARITY_THRESHOLD = 0.75;

    private LlmJournalAnalysisResult callLlm(EntryContext ctx) {
        SidecarClient.SidecarAnalysisResponse response = sidecarClient.analyze(ctx.title(), ctx.content());
        if (response == null || response.sections() == null || response.sections().isEmpty()) {
            throw new IllegalArgumentException("Sidecar returned empty analysis");
        }

        // Sections are independent of each other — process in parallel.
        // embeddingCache in SemanticLabelSelector is ConcurrentHashMap; UserLabelService
        // methods are @Transactional and safe for concurrent access via separate connections.
        List<LlmJournalAnalysisResult.LlmTopicSection> resolvedSections =
                response.sections().parallelStream()
                        .map(rawSec -> {
                            String resolvedTopic = resolveOrPersonalizeTopic(
                                    ctx.userId(), rawSec.rawTopic());
                            String resolvedEmotion = resolveOrPersonalizeEmotion(
                                    ctx.userId(), rawSec.rawEmotion());
                            return new LlmJournalAnalysisResult.LlmTopicSection(
                                    resolvedTopic,
                                    resolvedEmotion,
                                    rawSec.content(),
                                    rawSec.intensity(),
                                    rawSec.rawTopic(),
                                    rawSec.rawEmotion()
                            );
                        })
                        .toList();

        String dominantMood = response.summary().dominantMood();
        String resolvedDominantMood = resolveOrPersonalizeEmotion(ctx.userId(), dominantMood);

        LlmJournalAnalysisResult.LlmEntrySummary generatedSummary = generateInsightAndCopingTip(
                ctx.title(),
                resolvedSections,
                resolvedDominantMood,
                response.summary().intensity(),
                response.summary().themeHints()
        );

        return new LlmJournalAnalysisResult(resolvedSections, generatedSummary);
    }

    /**
     * Resolves a topic keyphrase from the sidecar to a user label.
     *
     * <ol>
     *   <li>pgvector similarity ≥ 0.75 → return existing user label (preserves vocabulary)</li>
     *   <li>No match → use the keyphrase directly as the display label, store it</li>
     * </ol>
     *
     * The old {@code personalizeLabel} LLM call has been removed. MiniLM keyphrases are already
     * usable English phrases — renaming them with a 14B model added ~60 seconds per section with
     * no quality improvement.
     *
     * Guard: if the keyphrase is unusably short (e.g. a single stopword leaked through), fall back
     * to "general" rather than storing a useless label.
     */
    private String resolveOrPersonalizeTopic(UUID userId, String rawTopic) {
        List<SemanticLabelSelector.LabelCandidate> candidates =
                semanticLabelSelector.findRelevantTopics(userId, rawTopic, "", 1);
        if (!candidates.isEmpty() && candidates.get(0).relevanceScore() >= SIMILARITY_THRESHOLD) {
            return candidates.get(0).displayText();
        }
        // No existing label matches — use the sidecar keyphrase directly.
        if (rawTopic == null || rawTopic.trim().length() < MIN_TOPIC_LENGTH) {
            return "general";
        }
        return rawTopic.trim();
    }

    /**
     * Resolves a GoEmotions emotion label to a user label.
     *
     * <ol>
     *   <li>pgvector similarity ≥ 0.75 → return existing user label (preserves vocabulary)</li>
     *   <li>No match → use the GoEmotions label directly as the display label, store it</li>
     * </ol>
     *
     * GoEmotions labels (admiration, fear, sadness, etc.) are human-readable and semantically
     * precise. They do not need an LLM to rename them. The old {@code personalizeLabel} call
     * was the primary source of the 15-minute processing time.
     */
    private String resolveOrPersonalizeEmotion(UUID userId, String rawEmotion) {
        List<SemanticLabelSelector.LabelCandidate> candidates =
                semanticLabelSelector.findRelevantEmotions(userId, rawEmotion, "", 1);
        if (!candidates.isEmpty() && candidates.get(0).relevanceScore() >= SIMILARITY_THRESHOLD) {
            return candidates.get(0).displayText();
        }
        // No existing label matches — use the GoEmotions label directly.
        if (rawEmotion == null || rawEmotion.isBlank()) {
            return "neutral";
        }
        return rawEmotion.trim();
    }

    private LlmJournalAnalysisResult.LlmEntrySummary generateInsightAndCopingTip(
            String title,
            List<LlmJournalAnalysisResult.LlmTopicSection> sections,
            String dominantMood,
            int overallIntensity,
            List<String> themeHints) {

        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(title != null ? title : "").append("\n");
        sb.append("Overall dominant mood: ").append(dominantMood).append("\n");
        sb.append("Overall emotional intensity: ").append(overallIntensity).append("\n");
        sb.append("Sections:\n");
        for (LlmJournalAnalysisResult.LlmTopicSection sec : sections) {
            sb.append("- Topic: ").append(sec.topic())
                    .append(", Emotion: ").append(sec.emotion())
                    .append(", Intensity: ").append(sec.intensity()).append("\n");
        }

        String system = """
                You are a warm, non-clinical self-reflection assistant.
                Based on the structured analysis of a user's journal entry, generate:
                1. "insight": 2-3 sentences of grounded, warm observation about the patterns or shifts in their entry. Do not give advice or say "you should".
                2. "copingTip": if the overall intensity is 4 or 5, write one actionable, gentle suggestion to help them cope. Otherwise, return null.

                Return ONLY a JSON object:
                {"insight": "...", "copingTip": "..." or null}
                """;

        try {
            record SimpleInsightResponse(String insight, String copingTip) {}
            SimpleInsightResponse res = chatClient.prompt()
                    .system(system)
                    .user(sb.toString())
                    .call()
                    .entity(SimpleInsightResponse.class);

            if (res != null) {
                return new LlmJournalAnalysisResult.LlmEntrySummary(
                        dominantMood,
                        overallIntensity,
                        res.insight(),
                        res.copingTip(),
                        themeHints,
                        dominantMood
                );
            }
        } catch (Exception e) {
            log.warn("Failed to generate insight/coping tip: {}", e.getMessage());
        }

        String fallbackInsight = "You processed thoughts around " + dominantMood + " today.";
        String fallbackCoping = overallIntensity >= 4 ? "Take a gentle breath and allow yourself some space." : null;
        return new LlmJournalAnalysisResult.LlmEntrySummary(
                dominantMood,
                overallIntensity,
                fallbackInsight,
                fallbackCoping,
                themeHints,
                dominantMood
        );
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
                    UserEmotionLabel aggregate = userLabelService.resolveEmotionFromModel(uid, sum.dominantMood(), sum.rawDominantMood());
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
                        UserTopicLabel tLabel = userLabelService.resolveTopicFromModel(uid, sec.topic(), sec.rawTopic());
                        UserEmotionLabel eLabel = userLabelService.resolveEmotionFromModel(uid, sec.emotion(), sec.rawEmotion());
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
