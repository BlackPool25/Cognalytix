package com.cognalytix.source.dto.user;

import java.util.Map;
import java.util.UUID;

public record UserLabelRef(
        UUID id,
        String label,
        String displayText,
        String category,
        String topic,
        String detail) {

    public static UserLabelRef flat(UUID id, String label) {
        return new UserLabelRef(id, label, label, null, null, null);
    }

    public static UserLabelRef from(Map<String, Object> labelData, UUID id, String label) {
        if (labelData == null || labelData.isEmpty()) {
            return flat(id, label);
        }
        return new UserLabelRef(
                id,
                label,
                String.valueOf(labelData.getOrDefault("display", label)),
                labelData.get("category") != null ? String.valueOf(labelData.get("category")) : null,
                labelData.get("topic") != null ? String.valueOf(labelData.get("topic")) : null,
                labelData.get("detail") != null ? String.valueOf(labelData.get("detail")) : null);
    }
}
