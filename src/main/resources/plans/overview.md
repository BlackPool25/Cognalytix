# 🧠 Cognalytix — Complete Implementation Blueprint
> Locked design as of April 2026. Every decision is final based on your answers.

---

## SECTION 0 — LOCKED DECISIONS SUMMARY

| Decision | Choice |
|---|---|
| Model | `qwen3:14b` via Ollama — configurable via `OLLAMA_MODEL` env var |
| Roles | `USER`, `ADMIN` only (no THERAPIST) |
| Auth | JWT access token + refresh token |
| AI trigger | Async background after entry save — user sees "Analyzing…" |
| AI output per entry | Mood (6 fixed) + intensity (1–5) + insight + coping tip + themes |
| Coping suggestions | Only when intensity ≥ 4 |
| DB storage | Structured fields only (no raw JSON) |
| Entry delete | Soft delete (`deleted_at` timestamp) |
| Insight cadence | Daily brief + weekly summary + monthly report — dashboard only |
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
- `POST /api/journals` — create entry; triggers async AI analysis
- `GET /api/journals` — paginated list (excludes soft-deleted), sorted by `created_at` DESC
- `GET /api/journals/{id}` — single entry + its mood analysis (if ready)
- `PUT /api/journals/{id}` — update title/content; re-triggers async AI analysis
- `DELETE /api/journals/{id}` — soft delete (sets `deleted_at`)
- Each entry has: `title`, `content`, `word_count` (auto-calculated), `analysis_status` (`PENDING` / `DONE` / `FAILED`)
- Ownership enforced: users can only access their own entries

### 1.3 AI Mood Analysis (Per Entry — Async)
- Triggered immediately after `POST` or `PUT /api/journals`
- Runs in Spring `@Async` thread pool — non-blocking
- Calls Ollama via Spring AI `ChatClient` with a structured prompt
- Returns and persists:
    - `mood_label`: one of `CALM | HAPPY | STRESSED | ANXIOUS | SAD | OVERWHELMED`
    - `mood_intensity`: integer 1–5
    - `insight`: 2–3 sentence reflection about the entry
    - `coping_tip`: one actionable suggestion — **only saved/shown if intensity ≥ 4**
    - `themes`: array of detected themes e.g. `["work stress", "relationships", "sleep"]`
- Frontend polls `GET /api/journals/{id}` every 3s until `analysis_status = DONE`

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

### 1.7 React User Dashboard
- Mood history chart (Recharts `LineChart`) — all-time, toggle weekly/monthly aggregation
- Mood distribution pie chart (all-time)
- Journaling streak counter
- "Today's Reflection" card (daily insight)
- "This Week" card (weekly insight)
- "Monthly Report" card
- Journal entry list with mood badge and intensity indicator
- "Analyzing…" skeleton state while AI processes

### 1.8 Analytics API (for Power BI)
- `GET /api/analytics/mood-distribution` — mood label counts (all-time, per user or global for admin)
- `GET /api/analytics/mood-over-time` — daily avg intensity + mood label, date range param
- `GET /api/analytics/theme-frequency` — top N themes ranked by occurrence
- `GET /api/analytics/streak-stats` — current streak, longest streak
- `GET /api/analytics/weekly-summary-history` — all weekly insight records for a user
- `GET /api/analytics/monthly-summary-history` — all monthly insight records
- All endpoints secured with JWT; admin endpoints return aggregated cross-user data
- Power BI Desktop connects via Web connector using Bearer token

### 1.9 Admin Panel
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

-- JOURNAL ENTRIES
CREATE TABLE journal_entries (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title            VARCHAR(255) NOT NULL,
  content          TEXT NOT NULL,
  word_count       INT NOT NULL DEFAULT 0,
  analysis_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | DONE | FAILED
  deleted_at       TIMESTAMP,  -- NULL = active; soft delete
  created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

-- MOOD ANALYSES (one per journal entry)
CREATE TABLE mood_analyses (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  entry_id     UUID NOT NULL UNIQUE REFERENCES journal_entries(id) ON DELETE CASCADE,
  user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mood_label   VARCHAR(20) NOT NULL,  -- CALM|HAPPY|STRESSED|ANXIOUS|SAD|OVERWHELMED
  intensity    SMALLINT NOT NULL CHECK (intensity BETWEEN 1 AND 5),
  insight      TEXT NOT NULL,
  coping_tip   TEXT,  -- NULL if intensity < 4
  themes       TEXT[],  -- PostgreSQL array: {"work stress","relationships"}
  created_at   TIMESTAMP NOT NULL DEFAULT NOW()
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
```

**Indexes to add:**
```sql
CREATE INDEX idx_entries_user_deleted ON journal_entries(user_id, deleted_at);
CREATE INDEX idx_analyses_user ON mood_analyses(user_id, created_at);
CREATE INDEX idx_daily_user_date ON daily_insights(user_id, insight_date);
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
| PUT | `/api/journals/{id}` | USER | Re-triggers async analysis |
| DELETE | `/api/journals/{id}` | USER | Soft delete — sets `deletedAt` |

### Insights
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/insights/daily/latest` | USER | Today's daily insight |
| GET | `/api/insights/daily` | USER | `?date=2026-04-28` |
| GET | `/api/insights/weekly/latest` | USER | Most recent weekly insight |
| GET | `/api/insights/monthly/latest` | USER | Most recent monthly insight |
| GET | `/api/insights/monthly/{monthYear}` | USER | e.g. `2026-04` |

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

### 4.1 Per-Entry Analysis Prompt (sent after every save)

```
You are a compassionate mental health journaling assistant.
Analyze the following journal entry and return ONLY valid JSON — no markdown, no explanation.

Journal entry:
"""
{journalContent}
"""

Return this exact JSON structure:
{
  "moodLabel": "<one of: CALM, HAPPY, STRESSED, ANXIOUS, SAD, OVERWHELMED>",
  "intensity": <integer 1-5, where 1=very mild, 5=very intense>,
  "insight": "<2-3 sentences reflecting on the emotional tone and what the user seems to be experiencing>",
  "copingTip": "<one specific, actionable suggestion — only include if intensity is 4 or 5, otherwise return null>",
  "themes": ["<theme1>", "<theme2>"]  // 1-3 short theme labels like "work stress", "relationships", "sleep", "loneliness"
}

Rules:
- moodLabel must be exactly one of the 6 options
- intensity must be an integer between 1 and 5
- themes array must have 1 to 3 items, each under 4 words
- copingTip must be null if intensity <= 3
- Return ONLY the JSON object, nothing else
```

### 4.2 Daily Insight Prompt

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

### 4.3 Weekly Summary Prompt

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

### 4.4 Monthly Report Prompt

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

---

## SECTION 5 — SPRING BOOT PACKAGE STRUCTURE

```
cognalytix-backend/
├── src/main/java/com/cognalytix/
│   ├── CognalytixApplication.java
│   │
│   ├── config/
│   │   ├── SecurityConfig.java          # JWT filter chain, CORS, role rules
│   │   ├── AsyncConfig.java             # ThreadPoolTaskExecutor for @Async
│   │   ├── OllamaConfig.java            # ChatClient bean with model from env
│   │   └── SchedulerConfig.java        # Enable scheduling
│   │
│   ├── auth/
│   │   ├── AuthController.java
│   │   ├── AuthService.java
│   │   ├── JwtService.java             # generate/validate access + refresh tokens
│   │   ├── JwtAuthFilter.java          # OncePerRequestFilter
│   │   └── dto/
│   │       ├── RegisterRequest.java
│   │       ├── LoginRequest.java
│   │       └── AuthResponse.java
│   │
│   ├── user/
│   │   ├── User.java                   # @Entity
│   │   ├── UserRepository.java
│   │   ├── RefreshToken.java           # @Entity
│   │   └── RefreshTokenRepository.java
│   │
│   ├── journal/
│   │   ├── JournalEntry.java           # @Entity
│   │   ├── JournalRepository.java
│   │   ├── JournalController.java
│   │   ├── JournalService.java
│   │   └── dto/
│   │       ├── JournalRequest.java
│   │       └── JournalResponse.java
│   │
│   ├── analysis/
│   │   ├── MoodAnalysis.java           # @Entity
│   │   ├── MoodAnalysisRepository.java
│   │   ├── MoodAnalysisService.java    # @Async trigger + Spring AI call
│   │   ├── MoodLabel.java              # enum
│   │   └── dto/
│   │       └── MoodAnalysisResponse.java
│   │
│   ├── insights/
│   │   ├── daily/
│   │   │   ├── DailyInsight.java       # @Entity
│   │   │   ├── DailyInsightRepository.java
│   │   │   ├── DailyInsightService.java
│   │   │   └── DailyInsightController.java
│   │   ├── weekly/
│   │   │   ├── WeeklyInsight.java
│   │   │   ├── WeeklyInsightRepository.java
│   │   │   ├── WeeklyInsightService.java
│   │   │   └── WeeklyInsightController.java
│   │   └── monthly/
│   │       ├── MonthlyInsight.java
│   │       ├── MonthlyInsightRepository.java
│   │       ├── MonthlyInsightService.java
│   │       └── MonthlyInsightController.java
│   │
│   ├── scheduler/
│   │   └── InsightScheduler.java       # @Scheduled — daily/weekly/monthly triggers
│   │
│   ├── analytics/
│   │   ├── AnalyticsController.java    # /api/analytics/** for Power BI + React
│   │   └── AnalyticsService.java       # Raw JPQL queries for aggregations
│   │
│   ├── admin/
│   │   ├── AdminController.java
│   │   └── AdminService.java
│   │
│   └── exception/
│       ├── GlobalExceptionHandler.java  # @RestControllerAdvice
│       ├── ResourceNotFoundException.java
│       └── UnauthorizedException.java
│
├── src/main/resources/
│   ├── application.yml
│   ├── application-docker.yml
│   └── prompts/                        # optional: store prompt templates as .st files
│       ├── entry-analysis.st
│       ├── daily-insight.st
│       ├── weekly-summary.st
│       └── monthly-report.st
│
├── Dockerfile
└── docker-compose.yml
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
          num-predict: 512
app:
  jwt:
    secret: ${JWT_SECRET:change-this-in-production-min-32-chars}
    access-token-expiry-ms: 900000      # 15 minutes
    refresh-token-expiry-days: 7

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

## SECTION 7 — ASYNC AI ANALYSIS FLOW (Code Sketch)

```java
// JournalService.java
@Service
public class JournalService {
    private final JournalRepository repo;
    private final MoodAnalysisService analysisService;

    public JournalResponse createEntry(JournalRequest req, UUID userId) {
        JournalEntry entry = new JournalEntry();
        entry.setUserId(userId);
        entry.setTitle(req.title());
        entry.setContent(req.content());
        entry.setWordCount(req.content().split("\\s+").length);
        entry.setAnalysisStatus(AnalysisStatus.PENDING);
        repo.save(entry);

        // Fire and forget — non-blocking
        analysisService.analyzeAsync(entry.getId());

        return JournalResponse.from(entry, null);
    }
}

// MoodAnalysisService.java
@Service
public class MoodAnalysisService {
    private final ChatClient chatClient;
    private final JournalRepository journalRepo;
    private final MoodAnalysisRepository analysisRepo;

    @Async("analysisExecutor")
    public void analyzeAsync(UUID entryId) {
        JournalEntry entry = journalRepo.findById(entryId).orElseThrow();
        try {
            MoodAnalysisResult result = chatClient.prompt()
                .user(buildPrompt(entry.getContent()))
                .call()
                .entity(MoodAnalysisResult.class);  // Spring AI structured output

            MoodAnalysis analysis = new MoodAnalysis();
            analysis.setEntryId(entryId);
            analysis.setUserId(entry.getUserId());
            analysis.setMoodLabel(MoodLabel.valueOf(result.moodLabel()));
            analysis.setIntensity(result.intensity());
            analysis.setInsight(result.insight());
            // Only persist coping tip if intensity >= 4
            analysis.setCopingTip(result.intensity() >= 4 ? result.copingTip() : null);
            analysis.setThemes(result.themes());
            analysisRepo.save(analysis);

            entry.setAnalysisStatus(AnalysisStatus.DONE);
        } catch (Exception e) {
            entry.setAnalysisStatus(AnalysisStatus.FAILED);
        }
        journalRepo.save(entry);
    }
}
```

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
│   │   └── analyticsApi.js
│   │
│   ├── context/
│   │   └── AuthContext.jsx      # stores accessToken, user, expiry
│   │
│   ├── pages/
│   │   ├── LoginPage.jsx
│   │   ├── RegisterPage.jsx
│   │   ├── DashboardPage.jsx    # mood chart + insight cards
│   │   ├── JournalListPage.jsx
│   │   ├── JournalDetailPage.jsx  # entry + analysis + polling
│   │   ├── NewEntryPage.jsx
│   │   ├── InsightsPage.jsx     # daily/weekly/monthly tabs
│   │   └── AdminPage.jsx        # Power BI iframe embed
│   │
│   ├── components/
│   │   ├── MoodChart.jsx        # Recharts LineChart (all-time + toggle)
│   │   ├── MoodPieChart.jsx     # Recharts PieChart
│   │   ├── InsightCard.jsx      # daily/weekly/monthly card
│   │   ├── MoodBadge.jsx        # colored badge by mood label
│   │   ├── AnalysisStatus.jsx   # "Analyzing…" skeleton + polling
│   │   └── StreakBanner.jsx
│   │
│   └── utils/
│       ├── moodColors.js        # { CALM: '#4ade80', ANXIOUS: '#f87171', ... }
│       └── dateHelpers.js
```

---

## SECTION 10 — 10-DAY BUILD PLAN

| Day | Focus | Concrete Deliverable |
|---|---|---|
| **1** | Project setup | Spring Boot init, Docker Compose up (Postgres + Ollama), `qwen3:14b` pulled, DB connected |
| **2** | Auth | `users` + `refresh_tokens` tables, register/login/refresh/logout endpoints, JWT filter, tested in Postman |
| **3** | Journal CRUD | `journal_entries` entity + repo + service + controller, soft delete, pagination, ownership guard |
| **4** | AI pipeline | `MoodAnalysisService` with `@Async`, Spring AI `ChatClient`, structured output to `mood_analyses` table, polling endpoint works |
| **5** | Insight schedulers | `DailyInsightService` + `WeeklyInsightService` + `MonthlyInsightService`, `InsightScheduler` with cron, all 3 prompts tested |
| **6** | Analytics API | All `/api/analytics/**` endpoints, JPQL aggregation queries, manual test with Postman, verify Power BI can connect |
| **7** | React core | Vite setup, Axios instance with JWT interceptor + refresh logic, Login/Register pages, Journal list + new entry page |
| **8** | React AI + charts | Polling "Analyzing…" state, analysis display, MoodChart (Recharts), InsightCards (daily/weekly/monthly) |
| **9** | Power BI + Admin | Power BI Desktop report built from analytics endpoints, published to web, iframe embedded in AdminPage |
| **10** | Polish + Docker | README, `.env.example`, full Docker Compose test, error handling, loading states, streak logic, smoke test all flows |

---

## SECTION 11 — TEST PLAN

| Layer | Test | Tool |
|---|---|---|
| Unit | `MoodAnalysisService` with mocked `ChatClient` | JUnit 5 + Mockito |
| Unit | `JwtService` — token generation, expiry, refresh | JUnit 5 |
| Unit | Streak calculation logic | JUnit 5 |
| Integration | Full auth flow: register → login → access protected → refresh → logout | `@SpringBootTest` + MockMvc |
| Integration | Journal create → verify `analysis_status=PENDING` → verify `DONE` after async | `@SpringBootTest` + `Awaitility` |
| Integration | Soft delete — entry gone from list, data present in analytics | MockMvc |
| Integration | Admin cannot access USER endpoints and vice versa | MockMvc with `@WithMockUser` |
| Data | Analytics queries return correct aggregations | `@DataJpaTest` with test data |
| Manual | Full React flow in browser | Chrome DevTools |
| Manual | Power BI connects to analytics endpoints | Power BI Desktop |

---

## SECTION 12 — RESUME TALKING POINTS

When asked about this project in interviews, you can say:

- *"I built an async AI pipeline using Spring AI's `ChatClient` with structured output — the LLM returns typed Java records, which I persist as relational data for downstream analytics."*
- *"I implemented JWT auth with refresh token rotation — access tokens expire in 15 minutes, refresh tokens are stored hashed in PostgreSQL and invalidated on logout."*
- *"I used Spring's `@Async` with a custom `ThreadPoolTaskExecutor` to decouple AI analysis from the HTTP response cycle — users get immediate feedback while the LLM processes in the background."*
- *"I exposed a set of analytics REST endpoints that Power BI Desktop consumes via its Web connector — the report is embedded in the React admin panel using Power BI's publish-to-web iframe."*
- *"I designed three insight cadences — daily, weekly, monthly — each using a different aggregation window and prompt strategy, all scheduled via Spring's `@Scheduled` with cron expressions."*