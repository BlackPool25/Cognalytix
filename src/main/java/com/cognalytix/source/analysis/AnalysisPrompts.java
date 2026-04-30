package com.cognalytix.source.analysis;

import java.util.ArrayList;
import java.util.List;

/** Topic/emotion sectioning + aggregate mood; vocabulary block is assembled per request. */
public final class AnalysisPrompts {

    /** Cap sent to the model so prompts stay bounded. */
    public static final int MAX_LABELS_EACH_AXIS = 120;

    private static final String CORE_RULES = """
            You are a self-discovery mirror for journaling — not a therapist. Read the journal below and return ONLY one valid JSON object — no markdown, no backticks, no extra text.

            Segment into coherent **topic** blocks (not by paragraph breaks alone). Each section has topic, emotion, content excerpt, intensity 1–5.

            VOCABULARY RULES (critical):
            - You are given this user's existing **topic** and **emotion** labels as bullet lists (may be empty).
            - When a section fits an existing label, set "topic" or "emotion" to **exactly one string from that list** — copy spelling character-for-character from the list you were given.
            - Only when **none** of the listed labels fit should you invent a **new** short label (under ~6 words). For that entry, reuse the **same exact string** for every section that shares that topic or emotion — do not invent synonyms ("sad" vs "feeling sad") for the same shade of meaning inside this JSON.
            - Before writing JSON, mentally decide the distinct topics and emotions for this entry; map each section to one of those strings.

            Summary object for the whole entry:
            - "dominantMood": must be **exactly one** of the "emotion" strings you used in sections, OR exactly one existing emotion from the user's list if it fits — never a wording variant not used elsewhere in your JSON.
            - "intensity" 1–5 overall; "insight" 2–3 sentences of grounded observation (what showed up, how it sat together) — not advice, not homework, no "you should"; "copingTip" JSON null if overall intensity ≤ 3, else one suggestion; "themeHints" 1–3 short strings.

            Strings: plain text, no newlines inside JSON values. Warm, non-clinical tone; do not diagnose; no markdown inside JSON.

            Shape:
            {"sections":[{"topic":"","emotion":"","content":"","intensity":1}],"summary":{"dominantMood":"","intensity":1,"insight":"","copingTip":null,"themeHints":["",""]}}
            """.strip();

    /**
     * Full system prompt including this user's saved labels so the model can reuse them verbatim or add a
     * minimal consistent set for this entry only.
     */
    public static String buildSystemPrompt(List<String> topicLabels, List<String> emotionLabels) {
        StringBuilder sb = new StringBuilder(CORE_RULES);
        sb.append("\n\n--- USER'S SAVED TOPICS (reuse exact spelling when applicable; empty means none yet) ---\n");
        if (topicLabels.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String t : topicLabels) {
                sb.append("- ").append(t).append('\n');
            }
        }
        sb.append("\n--- USER'S SAVED EMOTIONS (reuse exact spelling when applicable; empty means none yet) ---\n");
        if (emotionLabels.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String e : emotionLabels) {
                sb.append("- ").append(e).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** Copy lists defensively and truncate for prompt size. */
    public static List<String> truncateLabels(List<String> labels) {
        if (labels.size() <= MAX_LABELS_EACH_AXIS) {
            return List.copyOf(labels);
        }
        return new ArrayList<>(labels.subList(0, MAX_LABELS_EACH_AXIS));
    }

    public static String userPayload(String title, String content) {
        String t = title == null ? "" : title;
        String c = content == null ? "" : content;
        if (c.length() > 48_000) {
            c = c.substring(0, 48_000) + "…[truncated]";
        }
        return "Title: " + t + "\n\nJournal text:\n" + c;
    }

    private AnalysisPrompts() {}
}
