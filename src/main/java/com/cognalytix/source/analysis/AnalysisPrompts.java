package com.cognalytix.source.analysis;

import java.util.ArrayList;
import java.util.List;

public final class AnalysisPrompts {

    private static final String SEGMENTATION_GUIDANCE = """
EXAMPLES OF GOOD vs BAD SEGMENTATION:

GOOD — underlying themes emerge naturally:
Journal: "Had a rough morning with the code. Felt stupid. But then my teammate explained it and I felt relieved. Later had a great dinner with friends."
Sections:
  - topic: work/project stress, emotion: frustration, intensity: 4
  - topic: work/project stress, emotion: relief, intensity: 2
  - topic: social/relationships, emotion: joy, intensity: 3

BAD — one topic per paragraph, missing underlying themes:
  - topic: morning feelings, emotion: sadness, intensity: 4
  - topic: teammate help, emotion: calm, intensity: 2
  - topic: dinner, emotion: happiness, intensity: 3

GUIDANCE: Look for the UNDERLYING THEME behind multiple events. Group by meaning,
not by time-order. The topic label represents a life area — multiple events in the
same life area (work, relationships, health) share the same topic label if they
share the same emotional territory. A single journal entry may have 1-5 distinct
topics depending on content breadth.
""".strip();

    private static final String HIERARCHY_RULES = """
LABEL HIERARCHY RULES (for new labels you create):
- Maximum 3 levels: category_topic_detail (e.g., work_project_stress_deadline)
- Levels separated by underscores: work_project_stress
- Multi-word words within a level separated by hyphens: frustration_mild-melancholy
- Examples:
  - "career/job hunting" → career_job_hunting
  - "work pressure from deadlines" → work_project_deadline-pressure
  - "feeling down with acceptance" → emotional_mood_low-melancholy
- Never use slashes in the label — slashes are only for readability in examples
- The label you write is what gets stored and shown to the user exactly as-is
- For emotions: use hyphens within the same word cluster (e.g., frustration_mild-melancholy, social_joy_deep-connection)
""".strip();

    private static final String CORE_RULES = """
You are a self-discovery mirror for journaling — not a therapist. Read the journal below and return ONLY one valid JSON object — no markdown, no backticks, no extra text.

%s

%s

Segment into coherent **topic** blocks (not by paragraph breaks alone). Each section has topic, emotion, content excerpt, intensity 1–5.

VOCABULARY RULES (critical):
- You are given this user's existing **topic** and **emotion** labels as bullet lists (may be empty).
- When a section fits an existing label, set "topic" or "emotion" to **exactly one string from that list** — copy spelling character-for-character from the list you were given.
- Only when **none** of the listed labels fit should you invent a **new** short label following the hierarchy rules above. For that entry, reuse the **same exact string** for every section that shares that topic or emotion — do not invent synonyms ("sad" vs "feeling sad") for the same shade of meaning inside this JSON.
- Before writing JSON, mentally decide the distinct topics and emotions for this entry; map each section to one of those strings.

Summary object for the whole entry:
- "dominantMood": must be **exactly one** of the "emotion" strings you used in sections, OR exactly one existing emotion from the user's list if it fits — never a wording variant not used elsewhere in your JSON.
- "intensity" 1–5 overall; "insight" 2–3 sentences of grounded observation (what showed up, how it sat together) — not advice, not homework, no "you should"; "copingTip" JSON null if overall intensity ≤ 3, else one suggestion; "themeHints" 1–3 short strings.

Strings: plain text, no newlines inside JSON values. Warm, non-clinical tone; do not diagnose; no markdown inside JSON.

Shape:
{"sections":[{"topic":"","emotion":"","content":"","intensity":1}],"summary":{"dominantMood":"","intensity":1,"insight":"","copingTip":null,"themeHints":["",""]}}
""".strip().formatted(SEGMENTATION_GUIDANCE, HIERARCHY_RULES);

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