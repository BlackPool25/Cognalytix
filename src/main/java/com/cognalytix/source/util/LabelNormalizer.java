package com.cognalytix.source.util;

import java.util.Locale;
import java.util.Objects;

/** Lowercases, trims, and collapses whitespace for stable per-user deduplication. */
public final class LabelNormalizer {

    private LabelNormalizer() {}

    public static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? "" : t.replaceAll("\\s+", " ");
    }
}
