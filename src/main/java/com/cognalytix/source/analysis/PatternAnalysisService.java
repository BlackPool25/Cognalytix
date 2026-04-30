package com.cognalytix.source.analysis;

import com.cognalytix.source.domain.journal.GrowthDirection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL-side pattern detection ("SQL detects"); returns aggregate facts only — no raw journal text.
 */
@Service
public class PatternAnalysisService {

    private final JdbcTemplate jdbc;

    public PatternAnalysisService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Emotion drift on a topic {@code family_key} present in this entry, compared to all prior entries for the user.
     */
    public Optional<EmotionDriftFacts> findPostEntryEmotionDrift(
            UUID userId, UUID entryId, Instant entryCreatedAt, int totalJournalCount) {
        if (totalJournalCount < 3) {
            return Optional.empty();
        }
        List<String> families = distinctTopicFamiliesForEntry(entryId);
        for (String topicFamily : families) {
            int priorJournalCount = countPriorJournalsTouchingFamily(userId, topicFamily, entryCreatedAt);
            if (priorJournalCount < 2) {
                continue;
            }
            TopicRef topicRef = primaryTopicRefForFamily(entryId, topicFamily);
            if (topicRef == null) {
                continue;
            }
            FamAgg priorTop = dominantEmotionAgg(userId, topicFamily, entryCreatedAt);
            FamAgg currTop = dominantEmotionAggForEntry(entryId, topicFamily);
            if (priorTop == null || currTop == null) {
                continue;
            }
            boolean emotionChanged =
                    !priorTop.familyKey().equalsIgnoreCase(currTop.familyKey());
            boolean intensitySwing =
                    Math.abs(priorTop.avgIntensity() - currTop.avgIntensity()) >= 1.0;
            if (!emotionChanged && !intensitySwing) {
                continue;
            }
            int priorSections = countPriorSections(userId, topicFamily, entryCreatedAt);
            int currentSections = countCurrentSectionsForFamily(entryId, topicFamily);
            GrowthDirection dir =
                    EmotionDriftFacts.classifyDirection(
                            priorTop.familyKey(),
                            priorTop.avgIntensity(),
                            currTop.familyKey(),
                            currTop.avgIntensity());
            return Optional.of(
                    new EmotionDriftFacts(
                            topicRef.labelId(),
                            topicFamily,
                            topicRef.displayLabel(),
                            priorTop.familyKey(),
                            priorTop.avgIntensity(),
                            priorSections,
                            priorJournalCount,
                            currTop.familyKey(),
                            currTop.avgIntensity(),
                            currentSections,
                            dir));
        }
        return Optional.empty();
    }

    private List<String> distinctTopicFamiliesForEntry(UUID entryId) {
        return jdbc.query(
                """
                SELECT DISTINCT utl.family_key
                FROM journal_entry_sections jes
                JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
                WHERE jes.entry_id = ?
                """,
                (rs, i) -> rs.getString(1),
                entryId);
    }

    private int countPriorJournalsTouchingFamily(UUID userId, String topicFamily, Instant before) {
        Integer n =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(DISTINCT je.id)
                        FROM journal_entry_sections jes
                        JOIN journal_entries je ON jes.entry_id = je.id
                        JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
                        WHERE jes.user_id = ?
                          AND utl.family_key = ?
                          AND je.deleted_at IS NULL
                          AND je.created_at < ?
                        """,
                        Integer.class,
                        userId,
                        topicFamily,
                        Timestamp.from(before));
        return n == null ? 0 : n;
    }

    private int countPriorSections(UUID userId, String topicFamily, Instant before) {
        Integer n =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM journal_entry_sections jes
                        JOIN journal_entries je ON jes.entry_id = je.id
                        JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
                        WHERE jes.user_id = ?
                          AND utl.family_key = ?
                          AND je.deleted_at IS NULL
                          AND je.created_at < ?
                        """,
                        Integer.class,
                        userId,
                        topicFamily,
                        Timestamp.from(before));
        return n == null ? 0 : n;
    }

    private FamAgg dominantEmotionAgg(UUID userId, String topicFamily, Instant beforeExclusive) {
        List<FamAgg> rows =
                jdbc.query(
                        """
                        SELECT uel.family_key AS fk, COUNT(*) AS cnt, AVG(jes.intensity) AS avg_i
                        FROM journal_entry_sections jes
                        JOIN journal_entries je ON jes.entry_id = je.id
                        JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
                        JOIN user_emotion_labels uel ON jes.emotion_label_id = uel.id
                        WHERE jes.user_id = ?
                          AND utl.family_key = ?
                          AND je.deleted_at IS NULL
                          AND je.created_at < ?
                        GROUP BY uel.family_key
                        ORDER BY COUNT(*) DESC, AVG(jes.intensity) DESC
                        """,
                        (rs, i) ->
                                new FamAgg(
                                        rs.getString("fk"),
                                        rs.getLong("cnt"),
                                        rs.getDouble("avg_i")),
                        userId,
                        topicFamily,
                        Timestamp.from(beforeExclusive));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private FamAgg dominantEmotionAggForEntry(UUID entryId, String topicFamily) {
        List<FamAgg> rows =
                jdbc.query(
                        """
                        SELECT uel.family_key AS fk, COUNT(*) AS cnt, AVG(jes.intensity) AS avg_i
                        FROM journal_entry_sections jes
                        JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
                        JOIN user_emotion_labels uel ON jes.emotion_label_id = uel.id
                        WHERE jes.entry_id = ?
                          AND utl.family_key = ?
                        GROUP BY uel.family_key
                        ORDER BY COUNT(*) DESC, AVG(jes.intensity) DESC
                        """,
                        (rs, i) ->
                                new FamAgg(
                                        rs.getString("fk"),
                                        rs.getLong("cnt"),
                                        rs.getDouble("avg_i")),
                        entryId,
                        topicFamily);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private int countCurrentSectionsForFamily(UUID entryId, String topicFamily) {
        Integer n =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM journal_entry_sections jes
                        JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
                        WHERE jes.entry_id = ?
                          AND utl.family_key = ?
                        """,
                        Integer.class,
                        entryId,
                        topicFamily);
        return n == null ? 0 : n;
    }

    private TopicRef primaryTopicRefForFamily(UUID entryId, String topicFamily) {
        List<TopicRef> list =
                jdbc.query(
                        """
                        SELECT utl.id AS label_id, utl.label AS display_label
                        FROM journal_entry_sections jes
                        JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
                        WHERE jes.entry_id = ? AND utl.family_key = ?
                        ORDER BY jes.sort_order ASC
                        LIMIT 1
                        """,
                        (rs, i) ->
                                new TopicRef(
                                        rs.getObject("label_id", UUID.class), rs.getString("display_label")),
                        entryId,
                        topicFamily);
        return list.isEmpty() ? null : list.getFirst();
    }

    private record FamAgg(String familyKey, long count, double avgIntensity) {}

    private record TopicRef(UUID labelId, String displayLabel) {}
}
