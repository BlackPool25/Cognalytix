package com.cognalytix.source.domain.journal;

import com.cognalytix.source.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "user_topic_labels",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "normalized_key"}))
public class UserTopicLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "normalized_key", nullable = false, length = 200)
    private String normalizedKey;

    @Column(name = "family_key", nullable = false, length = 100)
    private String familyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "label_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> labelData = Map.of();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (familyKey == null || familyKey.isBlank()) {
            familyKey = normalizedKey;
        }
        if (labelData == null || labelData.isEmpty()) {
            labelData = Map.of(
                "display", label != null ? label : "",
                "category", null,
                "topic", null,
                "detail", null
            );
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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getNormalizedKey() {
        return normalizedKey;
    }

    public void setNormalizedKey(String normalizedKey) {
        this.normalizedKey = normalizedKey;
    }

    public String getFamilyKey() {
        return familyKey;
    }

    public void setFamilyKey(String familyKey) {
        this.familyKey = familyKey;
    }

    public Map<String, Object> getLabelData() {
        return labelData;
    }

    public void setLabelData(Map<String, Object> labelData) {
        this.labelData = labelData != null ? labelData : Map.of();
    }

    public String getDisplayLabel() {
        if (labelData != null && labelData.containsKey("display")) {
            return String.valueOf(labelData.get("display"));
        }
        return label;
    }

    public String getCategory() {
        if (labelData != null) {
            Object cat = labelData.get("category");
            return cat != null ? String.valueOf(cat) : null;
        }
        return null;
    }

    public String getTopicLevel() {
        if (labelData != null) {
            Object t = labelData.get("topic");
            return t != null ? String.valueOf(t) : null;
        }
        return null;
    }

    public String getDetail() {
        if (labelData != null) {
            Object d = labelData.get("detail");
            return d != null ? String.valueOf(d) : null;
        }
        return null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}