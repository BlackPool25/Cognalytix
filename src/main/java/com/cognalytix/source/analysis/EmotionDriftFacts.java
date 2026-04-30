package com.cognalytix.source.analysis;

import com.cognalytix.source.domain.journal.GrowthDirection;

import java.util.UUID;

/**
 * Aggregates-only facts for emotion drift on one topic {@code family_key}; used for SQL + narration.
 */
public record EmotionDriftFacts(
        UUID topicLabelId,
        String topicFamilyKey,
        String topicDisplayLabel,
        String priorDominantEmotionFamily,
        double priorDominantAvgIntensity,
        int priorSectionCount,
        int priorDistinctJournalCount,
        String currentDominantEmotionFamily,
        double currentDominantAvgIntensity,
        int currentSectionCount,
        GrowthDirection direction) {

    public static GrowthDirection classifyDirection(
            String priorEmotionFamily,
            double priorAvg,
            String currentEmotionFamily,
            double currentAvg) {
        boolean famChange =
                priorEmotionFamily != null
                        && currentEmotionFamily != null
                        && !priorEmotionFamily.equalsIgnoreCase(currentEmotionFamily);
        if (famChange) {
            if (currentAvg < priorAvg - 0.25) {
                return GrowthDirection.GROWTH;
            }
            if (currentAvg > priorAvg + 0.25) {
                return GrowthDirection.REGRESSION;
            }
            return GrowthDirection.STABLE;
        }
        if (currentAvg < priorAvg - 0.5) {
            return GrowthDirection.GROWTH;
        }
        if (currentAvg > priorAvg + 0.5) {
            return GrowthDirection.REGRESSION;
        }
        return GrowthDirection.STABLE;
    }
}
