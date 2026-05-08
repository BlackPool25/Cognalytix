package com.cognalytix.source.domain.journal;

import com.cognalytix.source.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "growth_insights")
public class GrowthInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "insight_type", nullable = false, length = 20)
    private GrowthInsightType insightType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_entry_id")
    private JournalEntry triggerEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_label_id")
    private UserTopicLabel topicLabel;

    @Column(name = "topic_family", length = 100)
    private String topicFamily;

    @Column(name = "emotion_family", length = 100)
    private String emotionFamily;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pattern_data", nullable = false)
    private Map<String, Object> patternData = new HashMap<>();

    @Column(nullable = false, columnDefinition = "TEXT")
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private GrowthDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "pattern_type", length = 30)
    private PatternType patternType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (patternData == null) {
            patternData = new HashMap<>();
        }
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public GrowthInsightType getInsightType() {
        return insightType;
    }

    public void setInsightType(GrowthInsightType insightType) {
        this.insightType = insightType;
    }

    public JournalEntry getTriggerEntry() {
        return triggerEntry;
    }

    public void setTriggerEntry(JournalEntry triggerEntry) {
        this.triggerEntry = triggerEntry;
    }

    public UserTopicLabel getTopicLabel() {
        return topicLabel;
    }

    public void setTopicLabel(UserTopicLabel topicLabel) {
        this.topicLabel = topicLabel;
    }

    public String getTopicFamily() {
        return topicFamily;
    }

    public void setTopicFamily(String topicFamily) {
        this.topicFamily = topicFamily;
    }

    public String getEmotionFamily() {
        return emotionFamily;
    }

    public void setEmotionFamily(String emotionFamily) {
        this.emotionFamily = emotionFamily;
    }

    public Map<String, Object> getPatternData() {
        return patternData;
    }

    public void setPatternData(Map<String, Object> patternData) {
        this.patternData = patternData != null ? patternData : new HashMap<>();
    }

    public String getNarration() {
        return narration;
    }

    public void setNarration(String narration) {
        this.narration = narration;
    }

    public GrowthDirection getDirection() {
        return direction;
    }

    public void setDirection(GrowthDirection direction) {
        this.direction = direction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public PatternType getPatternType() {
        return patternType;
    }

    public void setPatternType(PatternType patternType) {
        this.patternType = patternType;
    }
}
