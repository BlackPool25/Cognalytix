package com.cognalytix.source.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * JSON contract returned by the LLM (then mapped to DB tables). See {@code docs/journal-analysis-schema.md}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmJournalAnalysisResult(
        @JsonProperty("sections") List<LlmTopicSection> sections,
        @JsonProperty("summary") LlmEntrySummary summary) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LlmTopicSection(
            @JsonProperty("topic") String topic,
            @JsonProperty("emotion") String emotion,
            @JsonProperty("content") String content,
            @JsonProperty("intensity") int intensity,
            @JsonProperty("rawTopic") String rawTopic,
            @JsonProperty("rawEmotion") String rawEmotion) {

        public LlmTopicSection(String topic, String emotion, String content, int intensity) {
            this(topic, emotion, content, intensity, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LlmEntrySummary(
            @JsonProperty("dominantMood") String dominantMood,
            @JsonProperty("intensity") int intensity,
            @JsonProperty("insight") String insight,
            @JsonProperty("copingTip") String copingTip,
            @JsonProperty("themeHints") List<String> themeHints,
            @JsonProperty("rawDominantMood") String rawDominantMood) {

        public LlmEntrySummary(String dominantMood, int intensity, String insight, String copingTip, List<String> themeHints) {
            this(dominantMood, intensity, insight, copingTip, themeHints, null);
        }
    }
}

