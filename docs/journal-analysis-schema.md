# Journal analysis — database and LLM contract

This document matches the code in `com.cognalytix.source.analysis` and Flyway migrations `V4`–`V7`.

## 1. Relational schema (PostgreSQL)

### `journal_entries` (V4, extended V7)

| Column | Type | Purpose |
|--------|------|--------|
| `analysis_status` | `PENDING` \| `DONE` \| `FAILED` | Coarse status for polling / UI. |
| `analysis_attempt_count` | int | Increments at the **start** of each async run. |
| `analysis_fail_count` | int | Increments when a run ends in `FAILED`. |
| `analysis_in_progress` | boolean | `true` while the worker holds the job (even before the LLM returns). |
| `last_analysis_error` | varchar(64) | Machine-readable code (e.g. `llm_unreachable`), never a stack trace. |

### `mood_analyses` (V5, extended V7)

One row per **entry** (unique `entry_id`).

| Column | Purpose |
|--------|--------|
| `mood_label` | Display string for the aggregate mood (mirrors the per-user label row). |
| `aggregate_emotion_label_id` | FK → `user_emotion_labels` (growing, per-user vocabulary). |
| `intensity` | 1–5, entry-level. |
| `insight` | 2–3 sentence reflection. |
| `coping_tip` | Set only if aggregate intensity ≥ 4. |
| `themes` | JSON array of short strings (from LLM `themeHints`). |

### `user_topic_labels` / `user_emotion_labels` (V7)

Per-user, deduplicated by `normalized_key` (see `LabelNormalizer`).

### `journal_entry_sections` (V7)

**Topic**-bound blocks (not raw paragraph breaks). Each row: `topic_label_id`, `emotion_label_id`, `content` (excerpt), `intensity` 1–5, `sort_order`.

---

## 2. LLM JSON (Ollama via Spring AI `ChatClient`)

Before each call, the backend loads this user’s saved topic and emotion **label strings** (sorted, capped at `AnalysisPrompts.MAX_LABELS_EACH_AXIS`) and injects them into the **system** prompt. The model is instructed to **reuse an existing string verbatim** when it fits, or invent **one** new short label per distinct meaning and use that **same string** for every section in **this entry** that shares it (no synonym variants in one response).

Persistence maps each returned `topic` / `emotion` / `dominantMood` through `UserLabelService.resolveTopicFromModel` / `resolveEmotionFromModel`: match by `normalized_key`, then case-insensitive match on stored `label`, then create a new row.

The model must return a **single JSON object** (no markdown) matching `LlmJournalAnalysisResult` in code:

```json
{
  "sections": [
    {
      "topic": "short topic label",
      "emotion": "user-facing emotion in that block",
      "content": "excerpt from the journal for this block",
      "intensity": 3
    }
  ],
  "summary": {
    "dominantMood": "aggregate emotion phrase",
    "intensity": 3,
    "insight": "2-3 sentences",
    "copingTip": null,
    "themeHints": ["theme1", "theme2"]
  }
}
```

- `copingTip` must be JSON `null` when overall intensity is 3 or below; otherwise one concrete suggestion.
- Intensities are integers **1–5** everywhere.
- Up to **32** sections and **8** theme hints are persisted; extra items are ignored at validation.

Model and URL: `application.yml` → `spring.ai.ollama` (`OLLAMA_MODEL`, `OLLAMA_BASE_URL`).

---

## 3. API (after commit)

Analysis is **not** run inside the HTTP transaction. `JournalService` publishes `JournalEntryAnalysisEvent` **after commit**; `JournalAnalysisEventListener` calls `runAnalysisAsync`.

| Trigger | When |
|--------|------|
| `POST /api/journals` | After create. |
| `PUT /api/journals/{id}` | After update (clears prior mood + sections). |
| `POST /api/journals/{id}/reanalyze` | Manual rerun (clears prior mood + sections, then schedules). |

Verify with `GET /api/journals/{id}` until `analysisStatus` is `DONE` or `FAILED`.

---

## 4. Error codes (`last_analysis_error`)

See `com.cognalytix.source.analysis.AnalysisErrorCode` (e.g. `llm_unreachable`, `llm_invalid_response`, `validation_failed`, `persist_failed`, `internal_error`).
