# Cognalytix — Updated Blueprint
> Revised April 2026 — incorporates self-discovery mirror identity, family clustering, pattern engine, and narration layer.

**Journal analysis (backend):** Per-entry AI is **not** limited to six fixed mood enums. The model returns **topic-bound sections** (each with topic, emotion, excerpt, intensity) plus an entry **summary** (dominant mood, overall intensity, insight, optional coping tip, theme hints). **Per-user** `user_topic_labels` / `user_emotion_labels` grow over time (`normalized_key` dedupe, plus **`family_key`** for semantic clustering — see Section B). The **system prompt** includes the user’s saved labels; the LLM chooses **reuse verbatim** vs **one new label per meaning** for that response (no synonym sprawl inside one JSON). Persistence uses `UserLabelService.resolveTopicFromModel` / `resolveEmotionFromModel`; **new** labels trigger `FamilyResolutionService.resolveFamily()`. Analysis runs **`@TransactionalEventListener(AFTER_COMMIT)`** so work starts only after the journal row is committed. `journal_entries` holds **analysis telemetry** (`analysis_attempt_count`, `analysis_fail_count`, `analysis_in_progress`, `last_analysis_error`). Toggle with `app.analysis.enabled` / `ANALYSIS_ENABLED`.

---

## CHANGE SUMMARY (What's New vs Original Blueprint)

| Area | Before | After |
|---|---|---|
| Product identity | Generic mood journal | Self-discovery mirror |
| Pattern detection | None | SQL-based PatternAnalysisService |
| AI role | Analysis only | Analysis + Explainability narration layer |
| Label matching | Exact normalized_key match | Family-based semantic clustering |
| Label creation | topic + emotion + normalized_key | + family_key assigned by LLM at creation |
| Insight types | Daily / Weekly / Monthly narrative | + POST_ENTRY mirror, WEEKLY growth, MONTHLY growth, MILESTONE |
| New table | — | growth_insights |
| New service | — | PatternAnalysisService, NarrationService, MilestoneService, FamilyResolutionService |
| Cold start | Not addressed | Handled via minimum entry thresholds |

---

## SECTION A — PRODUCT IDENTITY

**Core identity:** A self-discovery mirror. Not a therapist. Not a tracker. A tool that holds your own data up and shows you who you were, who you are, and how you've shifted — in your own language.

**The core emotional moment the app must reliably create:**
> *"I never noticed that about myself."*

Everything — features, UI, data model — serves this moment.

---

## SECTION B — EMOTION & TOPIC FAMILY CLUSTERING

### Why It's Needed
Exact `normalized_key` matching breaks the mirror. "sadness with acceptance," "feeling down," and "mild melancholy" are the same emotional territory. Without clustering, pattern detection sees no connection between them and returns nothing.

### Family Resolution — Option A (LLM assigns family at label creation)

**When:** Every time `UserLabelService` creates a *new* label (topic or emotion), immediately after saving it, call `FamilyResolutionService.resolveFamily()`.

**How the LLM call works:**

```
System:
You are a label classifier. Assign the new label to an existing 
family or create one. Return ONLY the family name, 2 words max, 
lowercase. No explanation.

User:
Existing emotion families for this user:
["self-doubt", "sadness", "anxiety", "joy", "acceptance"]

New label: "feeling down with acceptance"

Which family does this belong to?
If none fit closely, create a new family name (2 words max).
```

**Key rules:**
- Single word/short phrase response only — cheap, fast call
- Runs ONCE per new label ever. Not per entry.
- Families grow organically with the user's vocabulary
- A user who never writes about anger never builds that family

### Schema Changes

```sql
-- Add family_key to both label tables
ALTER TABLE user_emotion_labels
ADD COLUMN family_key VARCHAR(100);

ALTER TABLE user_topic_labels
ADD COLUMN family_key VARCHAR(100);

-- Index for pattern queries
CREATE INDEX idx_emotion_family ON user_emotion_labels(user_id, family_key);
CREATE INDEX idx_topic_family ON user_topic_labels(user_id, family_key);
```

### Updated UserLabelService Flow

```
resolveEmotionFromModel(userId, rawLabel)
  → normalize → lookup existing (normalized_key)
  → if exists: return existing label
  → if new: save label row
         → FamilyResolutionService.resolveFamily(userId, newLabel, existingFamilies)
         → update family_key on saved row
         → return label
```

Same flow for `resolveTopicFromModel`.

---

## SECTION C — PATTERN ENGINE

### Architecture Principle
**SQL detects. AI narrates. Never the other way around.**

Patterns are aggregations over structured relational data. The LLM only converts those results into a human-readable sentence.

### New Service: PatternAnalysisService

Three query types:

**1. Emotion Drift on Topic Family**
```sql
-- For a given topic family, find emotion family trend over time
SELECT 
  uel.family_key AS emotion_family,
  AVG(jes.intensity) AS avg_intensity,
  COUNT(*) AS occurrence_count,
  MIN(je.created_at) AS first_seen,
  MAX(je.created_at) AS last_seen
FROM journal_entry_sections jes
JOIN user_emotion_labels uel ON jes.emotion_label_id = uel.id
JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
JOIN journal_entries je ON jes.entry_id = je.id
WHERE jes.user_id = :userId
  AND utl.family_key = :topicFamily
  AND je.deleted_at IS NULL
GROUP BY uel.family_key
ORDER BY last_seen DESC
```

**2. Intensity Softening (first third vs last third of entries)**
```sql
WITH ranked AS (
  SELECT jes.intensity, je.created_at,
    NTILE(3) OVER (ORDER BY je.created_at) AS tercile
  FROM journal_entry_sections jes
  JOIN journal_entries je ON jes.entry_id = je.id
  JOIN user_topic_labels utl ON jes.topic_label_id = utl.id
  WHERE jes.user_id = :userId AND utl.family_key = :topicFamily
)
SELECT tercile, AVG(intensity) FROM ranked
WHERE tercile IN (1, 3)
GROUP BY tercile
```

**3. Emotional Range Growth**
```sql
-- Count distinct emotion families per month
SELECT 
  DATE_TRUNC('month', je.created_at) AS month,
  COUNT(DISTINCT uel.family_key) AS distinct_emotion_families
FROM journal_entry_sections jes
JOIN user_emotion_labels uel ON jes.emotion_label_id = uel.id
JOIN journal_entries je ON jes.entry_id = je.id
WHERE jes.user_id = :userId AND je.deleted_at IS NULL
GROUP BY month ORDER BY month
```

**4. Disappeared Topics (avoidance detection)**
```sql
-- Topics present in first half, absent in second half
-- (run in application layer: compare two sets of topic families)
```

**5. Recurring Triggers (cross-entry correlation)**
```sql
-- Topics with high intensity that precede another high-intensity entry next day
-- Application layer: find entries where intensity >= 4, check next day's entry
```

### Cold Start Handling

| Entry Count | What fires |
|---|---|
| < 3 | Nothing. No pattern card shown. Show onboarding prompt instead. |
| 3–9 | Only emotion drift if same topic appears 2+ times |
| 10–29 | Full post-entry patterns + basic weekly |
| 30+ | All patterns including milestone cards |

---

## SECTION D — NARRATION LAYER

### NarrationService

Takes structured pattern data (SQL results), calls `qwen3:14b`, returns one warm sentence or short paragraph.

**Example prompt for emotion drift (growth):**
```
You are a warm, non-clinical self-reflection assistant.
The user has shown growth. Tell them in one sentence.
Do not use therapy language.

Data:
Topic family: coding/project work
6 weeks ago: emotion family "self-doubt", avg intensity 4.1
Today: emotion family "sadness", avg intensity 2.3

Write one sentence that reflects this shift back to the user.
Reference the time gap. Be specific, not generic.
```

**Example prompt for regression (encouragement with receipts):**
```
The user has shown increased intensity on a topic.
Encourage them using their own past data as evidence.
Do not say "you've got this." Reference when they handled this before.

Data:
Topic family: work stress
Current avg intensity: 4.2 (this week)
Previous low: 2.1 (8 weeks ago, lasted 3 weeks)
```

**Key rule:** LLM never sees raw entry content in narration calls. Only the aggregated pattern data. Privacy + efficiency.

---

## SECTION E — NEW TABLE: growth_insights

```sql
CREATE TABLE growth_insights (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  insight_type      VARCHAR(20) NOT NULL,
  -- POST_ENTRY | WEEKLY | MONTHLY | MILESTONE
  trigger_entry_id  UUID REFERENCES journal_entries(id) ON DELETE SET NULL,
  topic_label_id    UUID REFERENCES user_topic_labels(id) ON DELETE SET NULL,
  topic_family      VARCHAR(100),
  emotion_family    VARCHAR(100),
  pattern_data      JSONB NOT NULL,   -- raw SQL aggregation results
  narration         TEXT NOT NULL,    -- LLM output (what user reads)
  direction         VARCHAR(15),      -- GROWTH | REGRESSION | STABLE
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_growth_insights_user 
ON growth_insights(user_id, insight_type, created_at DESC);
```

**Why separate `pattern_data` and `narration`:**
You can regenerate narration without re-querying SQL. If you improve your prompt later, re-narrate from stored pattern data. Cheap.

---

## SECTION F — THREE MIRROR MOMENTS (UX)

### Moment 1 — Post-Entry Mirror Card
**Trigger:** After `analysis_status = DONE`, if PatternAnalysisService finds drift on any topic family in this entry.

**Minimum requirement:** Same topic family appeared at least 2 times before this entry.

**What it shows:**
> *"6 weeks ago, coding brought up self-doubt at intensity 4. Today it's sadness at intensity 2. You're carrying this differently now."*

Frontend polls `GET /api/insights/growth/latest?entryId={id}` after analysis completes.

### Moment 2 — Weekly Reveal Card
**Trigger:** Sunday midnight, after existing WeeklyInsightJob.

**What it shows:** One pattern the user didn't notice that week.
> *"You wrote about work 4 times this week, never about relationships. Last month it was the opposite."*

Or intensity trend:
> *"Your average intensity around self-doubt dropped from 3.8 to 2.4 this week."*

### Moment 3 — Milestone Card
**Trigger:** Entry counts 10, 30, 90, 180, 365.

**What it shows:**
> *"In your first 30 entries, your most intense topic was self-doubt at 3.8 avg. It's now 2.1. That's not nothing."*

Milestone cards persist permanently — users should be able to revisit them.

---

## SECTION G — UPDATED FLOW ARCHITECTURE

```
Entry saved & committed
        ↓
[Existing] JournalAnalysisService
  → sections + emotion/topic labels saved
  → [NEW] FamilyResolutionService runs for any NEW labels created
        ↓
[NEW] PatternAnalysisService.runPostEntryPatterns(userId, entryId)
  → checks entry count threshold (minimum 3)
  → runs emotion drift query on topic families in this entry
  → if pattern found → NarrationService.narrate(patternData)
       → qwen3:14b call (pattern data only, not raw content)
       → saves to growth_insights (type = POST_ENTRY)
        ↓
Frontend polls → Mirror Card appears


Sunday midnight
        ↓
[Existing] WeeklyInsightJob
        ↓
[NEW] GrowthInsightJob.runWeeklyPatterns(all active users)
  → intensity trend queries per user
  → disappeared topic queries
  → NarrationService per user with findings
  → saves to growth_insights (type = WEEKLY)


1st of month
        ↓
[Existing] MonthlyInsightJob
        ↓
[NEW] GrowthInsightJob.runMonthlyPatterns(all active users)
  → intensity softening (first vs last tercile)
  → emotional range growth
  → NarrationService per user
  → MilestoneService.checkMilestones(userId, entryCount)
  → saves to growth_insights (type = MONTHLY | MILESTONE)
```

---

## SECTION H — NEW API ENDPOINTS

```
GET /api/insights/growth/latest?entryId={id}
→ most recent POST_ENTRY insight for this entry (null if not ready or threshold not met)

GET /api/insights/growth/weekly
→ latest WEEKLY growth insight

GET /api/insights/growth/monthly
→ latest MONTHLY growth insight

GET /api/insights/growth/milestones
→ all MILESTONE cards for this user (all time)

GET /api/insights/growth/history
→ paginated list of all growth insights (for a "your journey" view)
```

---

## SECTION I — UPDATED PACKAGE STRUCTURE

```
com.cognalytix.source/
├── analysis/
│   ├── [existing] JournalAnalysisService.java
│   ├── [existing] JournalAnalysisEventListener.java
│   ├── [NEW] FamilyResolutionService.java     # LLM call at label creation
│   ├── [NEW] PatternAnalysisService.java      # SQL aggregation queries
│   ├── [NEW] NarrationService.java            # LLM explainability layer
│   ├── [NEW] MilestoneService.java            # entry count milestone checks
│   └── [NEW] GrowthInsightJob.java            # scheduled weekly + monthly
│
├── domain/
│   ├── [existing] MoodAnalysis.java
│   ├── [existing] JournalEntrySection.java
│   ├── [existing] UserTopicLabel.java         # + familyKey field
│   ├── [existing] UserEmotionLabel.java       # + familyKey field
│   └── [NEW] GrowthInsight.java
│
└── service/
    └── [existing] UserLabelService.java       # updated to call FamilyResolutionService
```

*(Actual repo layout may nest under `com/cognalytix/source/journal`, `domain/journal`, etc. — align package paths with existing modules.)*

---

## SECTION 0 — LOCKED DECISIONS SUMMARY

| Decision | Choice |
|---|---|
| Product framing | **Self-discovery mirror** — core moment: *"I never noticed that about myself."* |
| Model | `qwen3:14b` via Ollama — configurable via `OLLAMA_MODEL` env var |
| Roles | `USER`, `ADMIN` only (no THERAPIST) |
| Auth | JWT access token + refresh token |
| AI trigger | Async **after DB commit** on create/update/reanalyze — user sees "Analyzing…" / polls `analysis_status` |
| AI output per entry | **Sections:** topic + emotion (from growing per-user vocab or new coinage) + excerpt + intensity. **Summary:** dominant mood (must align with section emotions), overall intensity, insight, coping tip (if intensity > 3), theme hints |
| Label families | **`family_key`** on topic/emotion labels; assigned once per new label via `FamilyResolutionService` (LLM) |
| Patterns | **SQL** (`PatternAnalysisService`); **narration** (`NarrationService`) — LLM sees aggregates only, never raw entry text |
| Growth insights | **`growth_insights`** table: `pattern_data` + `narration`; types POST_ENTRY, WEEKLY, MONTHLY, MILESTONE |
| Cold start | Entry-count thresholds (see Section C): no mirror card below 3 entries; staged feature rollout |
| Coping suggestions | Only when **overall** summary intensity ≥ 4 (persist `null` otherwise) |
| DB storage | Structured relational rows + FKs to label tables; `mood_analyses.themes` remains JSON array (theme hints) |
| Entry delete | Soft delete (`deleted_at` timestamp) |
| Insight cadence | Daily brief + weekly summary + monthly report — dashboard; **plus** mirror / growth insight moments (Section F) |
| Email | Not in scope |
| Mood chart | All-time view with weekly/monthly toggle |
| Power BI | Spring Boot `/api/analytics/**` → Power BI Web connector → iframe embed in React |
| Deployment | Docker Compose now → Railway later |
| Frontend | React + Vite + TailwindCSS + Recharts |

### Local development — Postgres ports

- **From your machine** (Spring Boot in the IDE, GUI clients, `psql` on the host): use **`localhost:5332`**. In `docker-compose.yml`, map host port **5332** to the container’s internal **5432** (`"5332:5432"`).
- **From the `backend` service inside Docker Compose**: use hostname **`postgres`** and port **`5432`** only. Other containers talk to Postgres on its *internal* port; **5332 is not reachable on the `postgres` hostname** inside the network.
- **Default JDBC for local IDE runs**: `jdbc:postgresql://localhost:5332/cognalytix` (override with `DB_URL` when the app runs fully inside Compose: `jdbc:postgresql://postgres:5432/cognalytix`).

---

## SECTION 1 — FULL FEATURE LIST

### 1.1 Auth & User Management
- `POST /api/auth/register` — register with name, email, password
- `POST /api/auth/login` — returns `accessToken` (15 min) + `refreshToken` (7 days)
- `POST /api/auth/refresh` — exchange refresh token for new access token
- `POST /api/auth/logout` — invalidate refresh token (stored in DB)
- Passwords hashed with BCrypt
- `@PreAuthorize` guards on all protected endpoints
- Admin can deactivate users; deactivated users get 403 on login

### 1.2 Journal Entry CRUD
- `POST /api/journals` — create entry; publishes analysis event when `app.analysis.enabled` is true
- `GET /api/journals` — paginated list (excludes soft-deleted), sorted by `created_at` DESC
- `GET /api/journals/{id}` — single entry + its mood analysis (if ready)
- `PUT /api/journals/{id}` — update title/content; re-triggers async AI analysis when `app.analysis.enabled` is true
- `POST /api/journals/{id}/reanalyze` — same user only; resets analysis job and runs LLM again (when analysis enabled)
- `DELETE /api/journals/{id}` — soft delete (sets `deleted_at`)
- Each entry has: `title`, `content`, `word_count` (auto-calculated), `analysis_status` (`PENDING` / `DONE` / `FAILED`), plus telemetry: `analysis_attempt_count`, `analysis_fail_count`, `analysis_in_progress`, `last_analysis_error` (machine-readable code, not stack traces)
- Ownership enforced: users can only access their own entries

### 1.3 AI Journal Analysis (Per Entry — Async)
- **When:** `JournalService` publishes `JournalEntryAnalysisEvent` after create/update/reanalyze; `JournalAnalysisEventListener` handles it with `@TransactionalEventListener(phase = AFTER_COMMIT)` so the entry is committed before `JournalAnalysisService` runs.
- **Where:** `@Async("analysisExecutor")` (`AsyncConfig` + `app.async.*` pool) — HTTP returns immediately.
- **LLM:** Spring AI `ChatClient`; **dynamic system prompt** from `AnalysisPrompts.buildSystemPrompt(topics, emotions)` — lists this user’s labels (sorted, capped at `MAX_LABELS_EACH_AXIS` = **120** per axis via `truncateLabels`). **User message** from `AnalysisPrompts.userPayload` (title + body, body truncated at ~48k chars).
- **Model contract:** Single JSON matching `LlmJournalAnalysisResult`: `sections[]` `{ topic, emotion, content, intensity }`, `summary` `{ dominantMood, intensity, insight, copingTip, themeHints }`. Rules in prompt: reuse list strings **character-for-character** when they fit; otherwise one new short label per distinct meaning and **reuse that exact string** across all sections in **this** response that share it; `dominantMood` must match section emotion wording (or a chosen list string).
- **Persistence:** Replace prior section rows for the entry; upsert **one** `mood_analyses` row per entry with `mood_label` display text + `aggregate_emotion_label_id` → `user_emotion_labels`. Each section row in `journal_entry_sections` references `topic_label_id` / `emotion_label_id`. Labels resolved via `UserLabelService.resolveTopicFromModel` / `resolveEmotionFromModel` (normalized key, then case-insensitive label match, then `findOrCreate`). **New labels:** after save, `FamilyResolutionService` sets **`family_key`**.
- **Post-analysis:** `PatternAnalysisService.runPostEntryPatterns` + `NarrationService` when thresholds met → `growth_insights` (`POST_ENTRY`). See Section G.
- **Coping tip:** Persist only when summary intensity ≥ 4 (`JournalAnalysisService` ignores blank tips below that threshold).
- **Disable:** `app.analysis.enabled: false` or `ANALYSIS_ENABLED=false` (e.g. tests) skips scheduling the job.
- Frontend polls `GET /api/journals/{id}` until `analysis_status` is `DONE` or `FAILED`; **mirror card:** `GET /api/insights/growth/latest?entryId=…` after DONE.

### 1.4 Daily Insights (LLM — runs every day)
- Scheduled job: every day at 8 PM (`@Scheduled`)
- Takes last 24 hrs of entries for each user who has at least 1 entry
- LLM generates:
    - Dominant mood of the day
    - One-line observation ("You seemed more anxious than usual today")
    - Coping tip if any intensity ≥ 4 entries exist that day
- Stored in `daily_insights` table
- Shown on dashboard as "Today's Reflection" card

### 1.5 Weekly Summary (LLM — every Sunday midnight)
- Looks at last 7 days of entries + mood analyses per user
- LLM generates:
    - Most frequent mood
    - Mood trend (improving / stable / declining)
    - Top 3 recurring themes
    - One paragraph narrative summary
    - Coping suggestions if avg intensity ≥ 4
- Stored in `weekly_insights` table
- Shown on dashboard as "This Week" card
- **Additional:** `GrowthInsightJob.runWeeklyPatterns` → SQL patterns + narration → `growth_insights` (`WEEKLY`) — weekly **reveal** mirror moment (Section F).

### 1.6 Monthly Report (LLM — 1st of each month)
- Looks at last 30 days per user
- LLM generates:
    - Month-in-review narrative
    - Mood distribution breakdown
    - Progress observation (vs last month if data exists)
    - Top themes across the month
    - Personalised coping plan (3 suggestions) if avg intensity ≥ 4
- Stored in `monthly_insights` table
- Shown on dashboard as "Monthly Report" card
- **Additional:** `GrowthInsightJob.runMonthlyPatterns` + `MilestoneService` → `growth_insights` (`MONTHLY` / `MILESTONE`).

### 1.7 Growth & mirror insights (API)
- `GET /api/insights/growth/latest?entryId=` — post-entry mirror when ready
- `GET /api/insights/growth/weekly` — latest weekly growth insight
- `GET /api/insights/growth/monthly` — latest monthly growth insight
- `GET /api/insights/growth/milestones` — all milestone cards
- `GET /api/insights/growth/history` — paginated journey view

### 1.8 React User Dashboard
- Mood history chart (Recharts `LineChart`) — all-time, toggle weekly/monthly aggregation
- Mood distribution pie chart (all-time)
- Journaling streak counter
- "Today's Reflection" card (daily insight)
- "This Week" card (weekly insight)
- "Monthly Report" card
- **Post-entry mirror card** + **milestone** gallery / history ("your journey")
- Journal entry list with mood badge and intensity indicator
- "Analyzing…" skeleton state while AI processes; **cold-start** messaging when entry count < thresholds (Section C)

### 1.9 Analytics API (for Power BI)
- `GET /api/analytics/mood-distribution` — mood label counts (all-time, per user or global for admin)
- `GET /api/analytics/mood-over-time` — daily avg intensity + mood label, date range param
- `GET /api/analytics/theme-frequency` — top N themes ranked by occurrence
- `GET /api/analytics/streak-stats` — current streak, longest streak
- `GET /api/analytics/weekly-summary-history` — all weekly insight records for a user
- `GET /api/analytics/monthly-summary-history` — all monthly insight records
- All endpoints secured with JWT; admin endpoints return aggregated cross-user data
- Power BI Desktop connects via Web connector using Bearer token

### 1.10 Admin Panel
- `GET /api/admin/users` — paginated user list with stats (entry count, last active, avg mood intensity)
- `PUT /api/admin/users/{id}/deactivate` — deactivate account
- `GET /api/admin/analytics/global` — platform-wide mood stats (for Power BI admin report)

---

## SECTION 2 — DATABASE SCHEMA

```sql
-- USERS
CREATE TABLE users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name          VARCHAR(100) NOT NULL,
  email         VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role          VARCHAR(20)  NOT NULL DEFAULT 'USER',  -- USER | ADMIN
  is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
  streak_count  INT          NOT NULL DEFAULT 0,
  last_entry_date DATE,
  created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- REFRESH TOKENS
CREATE TABLE refresh_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token       TEXT NOT NULL UNIQUE,
  expires_at  TIMESTAMP NOT NULL,
  revoked     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- JOURNAL ENTRIES (see Flyway V4 + V7 for incremental ALTERs)
CREATE TABLE journal_entries (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title                    VARCHAR(255) NOT NULL,
  content                  TEXT NOT NULL,
  word_count               INT NOT NULL DEFAULT 0,
  analysis_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | DONE | FAILED
  analysis_attempt_count   INT NOT NULL DEFAULT 0,
  analysis_fail_count      INT NOT NULL DEFAULT 0,
  analysis_in_progress     BOOLEAN NOT NULL DEFAULT FALSE,
  last_analysis_error      VARCHAR(64),  -- machine-readable e.g. llm_unreachable
  deleted_at               TIMESTAMP,
  created_at               TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at               TIMESTAMP NOT NULL DEFAULT NOW()
);

-- PER-USER VOCABULARY (Flyway V7): dedupe by normalized_key per user; + family_key (Section B)
CREATE TABLE user_topic_labels (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label           VARCHAR(200) NOT NULL,
  normalized_key  VARCHAR(200) NOT NULL,
  family_key      VARCHAR(100),  -- semantic family for pattern SQL; set on first create
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, normalized_key)
);

CREATE TABLE user_emotion_labels (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label           VARCHAR(200) NOT NULL,
  normalized_key  VARCHAR(200) NOT NULL,
  family_key      VARCHAR(100),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, normalized_key)
);

-- MOOD ANALYSES (one per journal entry; V7 widens mood_label, adds FK)
CREATE TABLE mood_analyses (
  id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entry_id                     UUID NOT NULL UNIQUE REFERENCES journal_entries(id) ON DELETE CASCADE,
  user_id                      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mood_label                   VARCHAR(200) NOT NULL,
  aggregate_emotion_label_id   UUID REFERENCES user_emotion_labels(id) ON DELETE SET NULL,
  intensity                    SMALLINT NOT NULL CHECK (intensity BETWEEN 1 AND 5),
  insight                      TEXT NOT NULL,
  coping_tip                   TEXT,
  themes                       JSONB,
  created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- TOPIC-BOUND SECTIONS per entry (Flyway V7)
CREATE TABLE journal_entry_sections (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entry_id            UUID NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
  user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sort_order          INT NOT NULL,
  topic_label_id      UUID NOT NULL REFERENCES user_topic_labels(id) ON DELETE RESTRICT,
  emotion_label_id    UUID NOT NULL REFERENCES user_emotion_labels(id) ON DELETE RESTRICT,
  content             TEXT NOT NULL,
  intensity           SMALLINT NOT NULL CHECK (intensity BETWEEN 1 AND 5),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (entry_id, sort_order)
);

-- DAILY INSIGHTS
CREATE TABLE daily_insights (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  insight_date   DATE NOT NULL,
  dominant_mood  VARCHAR(20),
  observation    TEXT,
  coping_tip     TEXT,  -- NULL if no high-intensity entries that day
  created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, insight_date)
);

-- WEEKLY INSIGHTS
CREATE TABLE weekly_insights (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  week_start      DATE NOT NULL,
  week_end        DATE NOT NULL,
  dominant_mood   VARCHAR(20),
  mood_trend      VARCHAR(20),  -- IMPROVING | STABLE | DECLINING
  top_themes      TEXT[],
  narrative       TEXT,
  coping_plan     TEXT,  -- NULL if avg intensity < 4
  created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, week_start)
);

-- MONTHLY INSIGHTS
CREATE TABLE monthly_insights (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  month_year          VARCHAR(7) NOT NULL,  -- "2026-04"
  narrative           TEXT,
  mood_distribution   JSONB,  -- {"CALM":5,"ANXIOUS":3,...}
  top_themes          TEXT[],
  progress_note       TEXT,
  coping_plan         TEXT,  -- NULL if avg intensity < 4
  created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, month_year)
);

-- GROWTH / MIRROR INSIGHTS (Section E)
CREATE TABLE growth_insights (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  insight_type      VARCHAR(20) NOT NULL,  -- POST_ENTRY | WEEKLY | MONTHLY | MILESTONE
  trigger_entry_id  UUID REFERENCES journal_entries(id) ON DELETE SET NULL,
  topic_label_id    UUID REFERENCES user_topic_labels(id) ON DELETE SET NULL,
  topic_family      VARCHAR(100),
  emotion_family    VARCHAR(100),
  pattern_data      JSONB NOT NULL,
  narration         TEXT NOT NULL,
  direction         VARCHAR(15),  -- GROWTH | REGRESSION | STABLE
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Indexes to add:**
```sql
CREATE INDEX idx_entries_user_deleted ON journal_entries(user_id, deleted_at);
CREATE INDEX idx_analyses_user ON mood_analyses(user_id, created_at);
CREATE INDEX idx_user_topic_labels_user ON user_topic_labels(user_id);
CREATE INDEX idx_user_emotion_labels_user ON user_emotion_labels(user_id);
CREATE INDEX idx_emotion_family ON user_emotion_labels(user_id, family_key);
CREATE INDEX idx_topic_family ON user_topic_labels(user_id, family_key);
CREATE INDEX idx_journal_entry_sections_entry ON journal_entry_sections(entry_id);
CREATE INDEX idx_daily_user_date ON daily_insights(user_id, insight_date);
CREATE INDEX idx_growth_insights_user ON growth_insights(user_id, insight_type, created_at DESC);
```

---

## SECTION 3 — COMPLETE API CONTRACT

### Auth
| Method | Path | Auth | Request Body | Response |
|---|---|---|---|---|
| POST | `/api/auth/register` | Public | `{name, email, password}` | `{message}` |
| POST | `/api/auth/login` | Public | `{email, password}` | `{accessToken, refreshToken, user}` |
| POST | `/api/auth/refresh` | Public | `{refreshToken}` | `{accessToken, refreshToken}` |
| POST | `/api/auth/logout` | Bearer | `{refreshToken}` | `{message}` |

### Journals
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/journals` | USER | Returns entry with `analysis_status: PENDING` |
| GET | `/api/journals` | USER | `?page=0&size=10&sort=createdAt,desc` |
| GET | `/api/journals/{id}` | USER | Includes `moodAnalysis` object (null if PENDING) |
| PUT | `/api/journals/{id}` | USER | Update + re-triggers async analysis when analysis enabled |
| POST | `/api/journals/{id}/reanalyze` | USER | Re-runs analysis when `app.analysis.enabled` is true |
| DELETE | `/api/journals/{id}` | USER | Soft delete — sets `deletedAt` |

### Insights (legacy dashboard)
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/insights/daily/latest` | USER | Today's daily insight |
| GET | `/api/insights/daily` | USER | `?date=2026-04-28` |
| GET | `/api/insights/weekly/latest` | USER | Most recent weekly insight |
| GET | `/api/insights/monthly/latest` | USER | Most recent monthly insight |
| GET | `/api/insights/monthly/{monthYear}` | USER | e.g. `2026-04` |

### Growth & mirror insights (Section H)
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/insights/growth/latest` | USER | `?entryId=` — POST_ENTRY for that entry, or empty |
| GET | `/api/insights/growth/weekly` | USER | Latest WEEKLY growth insight |
| GET | `/api/insights/growth/monthly` | USER | Latest MONTHLY growth insight |
| GET | `/api/insights/growth/milestones` | USER | All MILESTONE rows for user |
| GET | `/api/insights/growth/history` | USER | Paginated all growth insights |

### Analytics (Power BI + React charts)
| Method | Path | Auth | Query Params |
|---|---|---|---|
| GET | `/api/analytics/mood-distribution` | USER | `?from=&to=` |
| GET | `/api/analytics/mood-over-time` | USER | `?groupBy=week\|month` |
| GET | `/api/analytics/theme-frequency` | USER | `?limit=10` |
| GET | `/api/analytics/streak` | USER | — |
| GET | `/api/analytics/weekly-history` | USER | — |
| GET | `/api/analytics/monthly-history` | USER | — |
| GET | `/api/analytics/global` | ADMIN | All users aggregated |

### Admin
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/admin/users` | ADMIN | Paginated |
| PUT | `/api/admin/users/{id}/deactivate` | ADMIN | Sets `is_active = false` |

---

## SECTION 4 — AI PROMPT TEMPLATES

### 4.1 Per-Entry Journal Analysis (after every save / reanalyze)

**Architecture:** Static core rules live in `AnalysisPrompts.CORE_RULES`. Each request builds **`AnalysisPrompts.buildSystemPrompt(topicLabels, emotionLabels)`** after loading this user’s labels (`UserLabelService.listTopicLabelTextsForPrompt` / `listEmotionLabelTextsForPrompt`, truncated to **120** per axis). User message = **`AnalysisPrompts.userPayload(title, content)`**.

**Expected JSON shape** (matches `LlmJournalAnalysisResult`; see `docs/journal-analysis-schema.md`):

```json
{
  "sections": [
    {
      "topic": "<verbatim from user's saved topics list when applicable, else new short label>",
      "emotion": "<verbatim from user's saved emotions list when applicable, else new short label>",
      "content": "<brief excerpt supporting this topic/emotion>",
      "intensity": 3
    }
  ],
  "summary": {
    "dominantMood": "<must match one section emotion string or a reused list label>",
    "intensity": 3,
    "insight": "<2–3 sentences>",
    "copingTip": null,
    "themeHints": ["hint1", "hint2"]
  }
}
```

**Rules (summarized from code):** Segment by coherent **topics**, not raw paragraphs only. Reuse saved labels **character-for-character** when they fit; invent **one** new label per distinct meaning and reuse **that exact string** for every section in this JSON that shares it—no synonyms within one response. `dominantMood` must align with section emotions. `copingTip` must be JSON `null` if overall intensity ≤ 3; backend persists coping text only when intensity ≥ 4. Return **only** the JSON object.

### 4.2 Family resolution (new label — Section B)

**System / user messages** as in Section B: classifier returns **only** a family name (2 words max, lowercase), no explanation.

### 4.3 Narration (pattern explainability — Section D)

**Principle:** Input is **only** structured `pattern_data` (SQL aggregates). No raw journal text.

Example prompts for **growth** and **regression** are given in Section D (emotion drift + intensity regression).

### 4.4 Daily Insight Prompt

```
You are a compassionate mental health assistant reviewing a user's journal entries from today.

Today's entries (most recent first):
{entriesSummary}

Mood analyses:
{analysesSummary}

Return ONLY valid JSON:
{
  "dominantMood": "<CALM|HAPPY|STRESSED|ANXIOUS|SAD|OVERWHELMED>",
  "observation": "<1 sentence warm, empathetic observation about their day>",
  "copingTip": "<one specific suggestion if any entry had intensity >= 4, otherwise null>"
}
```

### 4.5 Weekly Summary Prompt

```
You are a compassionate mental health assistant reviewing a user's week.

Entries this week: {entryCount}
Mood breakdown: {moodCounts}
Average intensity: {avgIntensity}
Recurring themes: {themes}

Return ONLY valid JSON:
{
  "dominantMood": "<mood>",
  "moodTrend": "<IMPROVING|STABLE|DECLINING>",
  "topThemes": ["<theme1>", "<theme2>", "<theme3>"],
  "narrative": "<2-3 sentence warm weekly reflection>",
  "copingPlan": "<2-3 sentences with actionable suggestions if avgIntensity >= 4, otherwise null>"
}
```

### 4.6 Monthly Report Prompt

```
You are a compassionate mental health assistant producing a monthly review.

Month: {monthYear}
Total entries: {entryCount}
Mood distribution: {moodDistribution}
Average intensity: {avgIntensity}
Top themes: {themes}
Previous month avg intensity (for comparison): {prevMonthAvgIntensity or "N/A"}

Return ONLY valid JSON:
{
  "narrative": "<3-4 sentence month-in-review>",
  "moodDistribution": { "CALM": 0, "HAPPY": 0, ... },
  "topThemes": ["<theme1>", "<theme2>", "<theme3>"],
  "progressNote": "<1-2 sentences comparing to last month, or null if no prior data>",
  "copingPlan": "<3 specific suggestions if avgIntensity >= 4, otherwise null>"
}
```

*(Daily / weekly / monthly prompts may still reference legacy six-mood enums until those jobs are fully migrated to the same free-form vocabulary as per-entry analysis.)*

---

## SECTION 5 — SPRING BOOT PACKAGE STRUCTURE (including mirror stack)

```
com.cognalytix.source/
├── src/main/java/com/cognalytix/source/
│   ├── CognalytixApplication.java
│   │
│   ├── config/
│   │   ├── SecurityConfig.java
│   │   ├── AsyncConfig.java              # ThreadPoolTaskExecutor bean name analysisExecutor
│   │   └── ...
│   │
│   ├── journal/ ... JournalController, JournalService ...
│   │
│   ├── analysis/
│   │   ├── JournalAnalysisService.java
│   │   ├── JournalAnalysisEventListener.java   # @TransactionalEventListener(AFTER_COMMIT)
│   │   ├── JournalEntryAnalysisEvent.java
│   │   ├── AnalysisPrompts.java
│   │   ├── LlmJournalAnalysisResult.java
│   │   ├── AnalysisErrorCode.java
│   │   ├── FamilyResolutionService.java        # NEW: LLM at label creation
│   │   ├── PatternAnalysisService.java        # NEW: SQL aggregations
│   │   ├── NarrationService.java              # NEW: mirrors / growth copy
│   │   ├── MilestoneService.java              # NEW: entry-count milestones
│   │   └── GrowthInsightJob.java              # NEW: weekly + monthly pattern runs
│   │
│   ├── service/
│   │   └── UserLabelService.java          # vocab; calls FamilyResolutionService on new labels
│   │
│   └── domain/journal/                    # MoodAnalysis, JournalEntrySection, User*Label + GrowthInsight, ...
│
├── docs/
│   └── journal-analysis-schema.md
│
├── src/main/resources/
│   ├── application.yml                    # spring.ai.ollama…; app.async.*; app.analysis.enabled
│   └── db/migration/                      # Flyway — add family_key, growth_insights
```

---

## SECTION 6 — KEY CONFIGURATION FILES

### application.yml
```yaml
spring:
  datasource:
    # Local IDE: host port 5332 → container 5432. In Compose backend: use DB_URL=jdbc:postgresql://postgres:5432/cognalytix
    url: ${DB_URL:jdbc:postgresql://localhost:5332/cognalytix}
    username: ${DB_USER:postgres}
    password: ${DB_PASS:postgres}
  jpa:
    hibernate:
      ddl-auto: validate          # use Flyway/Liquibase in prod; create-drop for dev
    show-sql: false
  ai:
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        model: ${OLLAMA_MODEL:qwen3:14b}
        options:
          temperature: 0.3        # low temp = consistent structured output
          num-predict: 2048       # room for sections + summary JSON

app:
  jwt:
    secret: ${JWT_SECRET:change-this-in-production-min-32-chars}
    access-token-expiry-ms: 900000      # 15 minutes
    refresh-token-expiry-days: 7

  analysis:
    enabled: ${ANALYSIS_ENABLED:true}   # false in tests / without Ollama

  async:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 100

  scheduler:
    daily-insight-cron: "0 0 20 * * *"    # 8 PM every day
    weekly-insight-cron: "0 0 0 * * SUN"  # Sunday midnight
    monthly-insight-cron: "0 0 1 1 * *"   # 1st of each month at 1 AM
```

### docker-compose.yml
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: cognalytix
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5332:5432"   # host:container — use localhost:5332 from the host; use postgres:5432 from other Compose services
    volumes:
      - pgdata:/var/lib/postgresql/data

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    # After first run: docker exec -it <container> ollama pull qwen3:14b

  backend:
    build: .
    ports:
      - "8080:8080"
    environment:
      # Always 5432 here (service DNS name `postgres`, not host port 5332)
      DB_URL: jdbc:postgresql://postgres:5432/cognalytix
      DB_USER: postgres
      DB_PASS: postgres
      OLLAMA_BASE_URL: http://ollama:11434
      OLLAMA_MODEL: qwen3:14b
      JWT_SECRET: your-super-secret-key-change-this-now
    depends_on:
      - postgres
      - ollama

volumes:
  pgdata:
  ollama_data:
```

---

## SECTION 7 — ASYNC JOURNAL ANALYSIS & MIRROR FLOW (target shape)

Per-entry analysis (existing):

```java
// JournalService — after successful save/update/reanalyze
if (analysisEnabled) {
    eventPublisher.publishEvent(new JournalEntryAnalysisEvent(entryId));
}

// JournalAnalysisEventListener
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onCommitted(JournalEntryAnalysisEvent event) {
    journalAnalysisService.analyzeAsync(event.entryId());
}

// JournalAnalysisService
@Async("analysisExecutor")
public void analyzeAsync(UUID entryId) {
    // beginOrAbort: telemetry flags + increment attempt
    // load EntryContext (userId, title, content) in @Transactional read
    // callLlm: ChatClient with AnalysisPrompts.buildSystemPrompt(...) + userPayload
    // saveSuccess: resolve labels via UserLabelService (+ FamilyResolutionService for new labels);
    //             replace sections; upsert mood_analyses
    // then: PatternAnalysisService.runPostEntryPatterns(userId, entryId) when enabled
    //   → NarrationService + persist growth_insights (POST_ENTRY) if pattern + thresholds
}
```

Scheduled jobs: existing daily/weekly/monthly insight jobs **plus** `GrowthInsightJob` for weekly/monthly pattern passes and milestones (Section G).

---

## SECTION 8 — POWER BI INTEGRATION DESIGN

### How it works end-to-end:
```
Spring Boot /api/analytics/** (JWT-secured)
        ↓
Power BI Desktop
  → Get Data → Web → Enter URL → Advanced → Add Authorization header
  → Transforms data into tables/measures
  → Publish to Power BI Service (free account)
        ↓
React Admin Panel
  → Embeds Power BI report iframe using Power BI Embed URL
  → No Power BI Premium needed — use "Publish to web" public embed
```

### Analytics endpoints Power BI will consume:
```
GET /api/analytics/mood-distribution
→ [ { moodLabel: "ANXIOUS", count: 12 }, ... ]

GET /api/analytics/mood-over-time?groupBy=week
→ [ { period: "2026-W14", avgIntensity: 3.2, dominantMood: "STRESSED" }, ... ]

GET /api/analytics/theme-frequency?limit=10
→ [ { theme: "work stress", count: 18 }, ... ]

GET /api/analytics/weekly-history
→ [ { weekStart, dominantMood, moodTrend, avgIntensity }, ... ]
```

`moodLabel` / aggregate mood values coming from journal analysis are **free-form strings** tied to each user's vocabulary (not limited to six enums). Shape analytics queries and Power BI visuals as **dynamic categorical labels**. **Family keys** can power grouped analytics later if exposed.

### Power BI visuals to build:
1. **Line chart** — mood intensity over time (weekly)
2. **Donut chart** — mood label distribution
3. **Bar chart** — theme frequency (top 10)
4. **Card KPIs** — total entries, avg intensity, current streak, dominant mood this month
5. **Table** — weekly insight history with trend indicator

### Embedding in React (free method):
```jsx
// AdminDashboard.jsx
// After publishing report: File → Publish to web → copy embed URL
const POWERBI_EMBED_URL = import.meta.env.VITE_POWERBI_EMBED_URL;

export default function AdminDashboard() {
  return (
    <div className="w-full h-screen">
      <iframe
        title="Cognalytix Analytics"
        src={POWERBI_EMBED_URL}
        allowFullScreen
        className="w-full h-full border-0"
      />
    </div>
  );
}
```
> ⚠️ "Publish to web" is public (no auth). For a private embed you need Power BI Premium (paid). For a portfolio demo, public embed is fine — just don't put real user data in it. Use seeded dummy data for the Power BI report.

---

## SECTION 9 — REACT FRONTEND STRUCTURE

```
Cognalytix-frontend/
├── src/
│   ├── api/
│   │   ├── axiosInstance.js     # base URL, JWT interceptor, refresh token logic
│   │   ├── authApi.js
│   │   ├── journalApi.js
│   │   ├── insightsApi.js
│   │   ├── growthInsightsApi.js # NEW: /api/insights/growth/**
│   │   └── analyticsApi.js
│   │
│   ├── context/
│   │   └── AuthContext.jsx      # stores accessToken, user, expiry
│   │
│   ├── pages/
│   │   ├── LoginPage.jsx
│   │   ├── RegisterPage.jsx
│   │   ├── DashboardPage.jsx    # mood chart + insight cards + growth history link
│   │   ├── JournalListPage.jsx
│   │   ├── JournalDetailPage.jsx  # entry + analysis + polling + mirror card
│   │   ├── NewEntryPage.jsx
│   │   ├── InsightsPage.jsx     # daily/weekly/monthly tabs; journey / milestones
│   │   └── AdminPage.jsx        # Power BI iframe embed
│   │
│   ├── components/
│   │   ├── MoodChart.jsx        # Recharts LineChart (all-time + toggle)
│   │   ├── MoodPieChart.jsx     # Recharts PieChart
│   │   ├── InsightCard.jsx      # daily/weekly/monthly card
│   │   ├── MirrorCard.jsx       # NEW: post-entry growth insight
│   │   ├── MilestoneCards.jsx   # NEW: persistent milestone display
│   │   ├── MoodBadge.jsx        # colored badge by mood label
│   │   ├── AnalysisStatus.jsx   # "Analyzing…" skeleton + polling
│   │   └── StreakBanner.jsx
│   │
│   └── utils/
│       ├── moodColors.js        # map labels / families to colors as needed
│       └── dateHelpers.js
```

---

## SECTION 10 — 10-DAY BUILD PLAN (UPDATED)

| Day | Focus | Deliverable |
|---|---|---|
| 1 | Project setup | Docker Compose, Postgres, Ollama, qwen3:14b pulled |
| 2 | Auth | Register, login, JWT, refresh, logout |
| 3 | Journal CRUD | Entries, soft delete, pagination, ownership |
| 4 | AI analysis pipeline | Sections, labels, existing vocab system |
| **4.5** | **Family clustering** | **FamilyResolutionService, family_key on label tables, Flyway migration** |
| 5 | Insight schedulers | Daily, weekly, monthly existing jobs |
| **5.5** | **Pattern engine** | **PatternAnalysisService, NarrationService, growth_insights table, POST_ENTRY flow** |
| 6 | Analytics API + Growth API | All `/api/analytics/**` + `/api/insights/growth/**` endpoints |
| 7 | React core | Auth, journal list, new entry |
| **8** | **Mirror moment UI** | **Post-entry mirror card, milestone cards, polling for growth insight** |
| 9 | Charts + weekly/monthly UI | MoodChart, InsightCards, growth history view |
| 10 | Polish + Docker | README, cold start states, error handling, full smoke test |

---

## SECTION 11 — TEST PLAN

| Layer | Test | Tool |
|---|---|---|
| Unit | `JournalAnalysisService` / label resolution with mocked `ChatClient` | JUnit 5 + Mockito |
| Unit | `FamilyResolutionService` — family assignment for new labels | JUnit 5 + Mockito |
| Unit | `PatternAnalysisService` — SQL aggregation boundaries / thresholds | `@DataJpaTest` or mocked repos |
| Unit | `NarrationService` — prompt build uses pattern payload only (no raw content) | JUnit 5 |
| Unit | `JwtService` — token generation, expiry, refresh | JUnit 5 |
| Unit | Streak calculation logic | JUnit 5 |
| Integration | Full auth flow: register → login → access protected → refresh → logout | `@SpringBootTest` + MockMvc |
| Integration | Journal create → `PENDING` → async completes `DONE` or `FAILED`; telemetry fields update | `@SpringBootTest` + `Awaitility` |
| Integration | `POST /api/journals/{id}/reanalyze` replaces sections + aggregate mood row | MockMvc |
| Integration | Growth insight APIs return expected shapes / pagination | MockMvc |
| Integration | Soft delete — entry gone from list, data present in analytics | MockMvc |
| Integration | Admin cannot access USER endpoints and vice versa | MockMvc with `@WithMockUser` |
| Data | Analytics queries return correct aggregations | `@DataJpaTest` with test data |
| Manual | Full React flow in browser | Chrome DevTools |
| Manual | Power BI connects to analytics endpoints | Power BI Desktop |

---

## SECTION 12 — RESUME TALKING POINTS (UPDATED)

- *"I built a semantic family clustering layer — when the LLM coins a new emotion or topic label, a secondary LLM call immediately classifies it into a user-specific family. This allows pattern detection across synonymous labels without hardcoding any taxonomy."*
- *"I separated pattern detection from narration — SQL aggregations detect the pattern, the LLM only converts structured results into human language. This keeps AI costs low and results deterministic."*
- *"I designed three distinct mirror moments — post-entry, weekly, and milestone — each with different minimum data thresholds to avoid cold-start empty states."*
- *"The growth_insights table stores both raw pattern_data and LLM narration separately, so narration can be regenerated from stored aggregations if prompts improve without re-querying the database."*
- *"I built an async AI pipeline using Spring AI's `ChatClient` with structured output — the LLM returns typed Java records, which I persist as relational data for downstream analytics."*
- *"I implemented JWT auth with refresh token rotation — access tokens expire in 15 minutes, refresh tokens are stored hashed in PostgreSQL and invalidated on logout."*
- *"I used Spring's `@Async` with a custom `ThreadPoolTaskExecutor` to decouple AI analysis from the HTTP response cycle — users get immediate feedback while the LLM processes in the background."*
- *"I exposed analytics REST endpoints that Power BI Desktop consumes via its Web connector — the report is embedded in the React admin panel using Power BI's publish-to-web iframe."*
- *"Journal analysis runs **after database commit** via `@TransactionalEventListener`, injects each user's **saved topic/emotion vocabulary** into the system prompt, and persists topic sections plus FK-linked labels (`normalized_key` dedupe + **family_key** for patterns)."*
