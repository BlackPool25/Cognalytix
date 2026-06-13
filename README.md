# Cognalytix — Backend

**Cognalytix** is a Spring Boot application that turns personal journal entries into structured self-discovery insights. It identifies emotional patterns across your life's topics, detects growth shifts, and narrates what it finds in your own language.

> *"I never noticed that about myself."* — the core moment Cognalytix creates.

## Stack

| Area | Choice |
|------|--------|
| Runtime | Java **25**, Spring Boot **4.0.x** |
| AI | Spring AI **2.0.0-M5** + Ollama (`qwen3.5:4b` default chat; `qwen3.5:0.8b` for label tasks; `qwen3-embedding:0.6b` for embeddings) |
| Sidecar | Local python service running quantized ONNX models for GoEmotions classification and MiniLM embeddings |
| Security | JWT access tokens + opaque HMAC'd refresh tokens in PostgreSQL; passwords: **SHA-256(pepper) + BCrypt** |
| Database | Spring Data JPA, PostgreSQL **16** + **pgvector**, Flyway migrations (V1–V12) |
| Frontend API | REST + JWT; API base `http://localhost:8000/api` by default |

## Prerequisites

- **Temurin JDK 25** — `pom.xml` targets 25. If IntelliJ shows "cannot find symbol" for domain types, set **File → Project Structure → Project** SDK to **25** and **Language level** to **25**, then Maven tool window → **Reload**, then **File → Invalidate Caches → Invalidate and Restart**. Open the **folder containing `pom.xml`** (i.e. `source/`), not a parent.
- **PostgreSQL 16** with the **`pgvector`** extension — `CREATE EXTENSION IF NOT EXISTS vector;`
- **Ollama** running locally on the host with models pulled:
  ```bash
  ollama pull qwen3.5:4b            # chat / narration / insights
  ollama pull qwen3.5:0.8b          # label tasks / fallback
  ollama pull qwen3-embedding:0.6b  # semantic label selection (1024-dim vectors)
  ```
- **Node.js 20+** if running the frontend locally.

## Quick Start

### 1. Start PostgreSQL
```bash
# Using the included compose file (postgres on host port 5332, ollama on 11434):
cd source && docker compose up -d postgres ollama
```

### 2. Set secrets (production only)
```bash
export JWT_SECRET=<your-256-bit-secret>          # env: JWT_SECRET
export REFRESH_STORAGE_SECRET=<your-random-secret> # env: REFRESH_STORAGE_SECRET
export PASSWORD_PEPPER=<32+ hex chars>            # env: PASSWORD_PEPPER
export ANALYSIS_ENABLED=true                        # env: ANALYSIS_ENABLED
```

### 3. Run
```bash
cd source && ./mvnw spring-boot:run
```

API starts at `http://localhost:8000`. Flyway auto-applies migrations on first startup.

## Configuration

All settings live in `src/main/resources/application.yml`. Key `app.*` properties:

| Property | Default | Description |
|---|---|---|
| `app.jwt.secret` | local dev default | HMAC key for access JWTs. Override: **`JWT_SECRET`** |
| `app.jwt.access-token-expiry-ms` | 900000 (15 min) | Access token lifetime |
| `app.jwt.refresh-token-expiry-days` | 7 | Refresh token lifetime |
| `app.jwt.refresh-storage-secret` | local default | HMAC key for opaque refresh tokens before storing |
| `app.security.password-pepper` | 64-char hex | Server pepper appended before BCrypt |
| `app.security.admin-allowed-emails` | *(empty)* | Optional comma-sep emails; restricts `/api/admin/**` when non-empty |
| `app.ollama.chat-model` | `qwen3.5:4b` | Primary chat/narration model. Override: **`OLLAMA_CHAT_MODEL`** |
| `app.ollama.label-model` | `qwen3.5:0.8b` | Label generation model. Override: **`OLLAMA_LABEL_MODEL`** |
| `app.ollama.embedding-model` | `qwen3-embedding:0.6b` | Embedding model name for semantic label matching. Override: **`OLLAMA_EMBEDDING_MODEL`** |
| `app.async.core-pool-size` | 4 | Thread pool for async journal analysis |
| `app.analysis.enabled` | `true` | Disables all Ollama calls (analysis, family clustering, mirror narration) |

Database URL and credentials use `spring.datasource.*` with env overrides **`DB_URL`**, **`DB_USER`**, **`DB_PASS`** (compose uses `jdbc:postgresql://postgres:5432/cognalytix`).

## Architecture

### Analysis Pipeline

```
POST/PUT /api/journals  →  JournalEntryAnalysisEvent (AFTER_COMMIT)
    → JournalAnalysisService.runAnalysisAsync()          [@Async("analysisExecutor")]
        1. callLlm() — Sends journal title/content to local Python ONNX Sidecar (:8001)
           which runs Roberta GoEmotions + MiniLM in one batched call to extract raw sentences,
           emotions, topics, and intensities.
        2. pgvector Similarity Match — Queries topic/emotion pgvector embeddings. If similarity
           >= 0.75, reuses the user's existing label to preserve vocabulary continuity.
           Otherwise, trusts the sidecar label directly and stores it as a new user label.
           (Costly LLM-based labeling/renaming is completely eliminated).
        3. generateInsightAndCopingTip() — Calls qwen3.5:4b once to generate a warm, warm narrative
           summary and optional coping suggestion.
        4. Persist: mood_analyses, journal_entry_sections, user_topic/emotion_labels
        5. PostEntryMirrorService.runAfterSuccessfulAnalysis()
            a. PatternAnalysisService.findPostEntryEmotionDrift() — SQL aggregates only
            b. MirrorNarrationService.narrate() — LLM reads structured facts, returns 5-field mirror card
            c. Persist: growth_insights (POST_ENTRY)
```

**Key principle: SQL detects patterns, AI narrates them.** Raw journal text never reaches the LLM for pattern detection.

### Label Selection — Semantic over Hard Cap

Users accumulate topic/emotion labels over time. To avoid duplicates and keep the vocabulary consistent, Cognalytix uses **vector embeddings** and **pgvector similarity** (rather than expensive LLM calls) to resolve them:

1. Sidecar extracts raw keyphrase topics (MiniLM) and emotions (GoEmotions).
2. Query `topic_label_embeddings` / `emotion_label_embeddings` using **cosine similarity**.
3. If similarity to an existing label is **>= 0.75**, that label is reused (preserving the user's vocabulary).
4. Otherwise, the sidecar label is saved as a new label, and its embedding is stored asynchronously via `EmbeddingStorageService`.

### Family Clustering

Labels are clustered into `family_key` by `FamilyResolutionService` (LLM call on new label creation, with retry). "Job pressure" and "work stress" both map to e.g. `work_stress`. This enables pattern detection across synonymous labels without hardcoding any taxonomy.

### Post-Entry Mirror Card (5 Fields)

When `PatternAnalysisService` detects an emotion drift or intensity shift across ≥2 prior entries on the same topic family, `MirrorNarrationService` generates a structured card:

| Field | Description |
|---|---|
| `headline` | Max 12 words; concrete, no advice |
| `trajectoryLine` | One sentence tying the past aggregate pattern to today |
| `dayAnchorLine` | Today's dominant mood + intensity + one theme |
| `integratedBody` | 2 short sentences; observation + integration |
| `direction` | GROWTH / REGRESSION / STABLE |

Direction is classified from `EmotionDriftFacts` (emotion family change + intensity delta thresholds) before the LLM call, and the LLM is instructed to match it.

---

## API Reference

### Authentication

All auth routes are **public** (no Bearer token):

| Method | Path | Body |
|---|---|---|
| POST | `/api/auth/register` | `{name, email, password}` |
| POST | `/api/auth/login` | `{email, password}` |
| POST | `/api/auth/refresh` | `{refreshToken}` |
| POST | `/api/auth/logout` | `{refreshToken}` (Bearer required) |

Register/login/refresh return:
```json
{
  "message": "...",
  "tokens": { "accessToken", "refreshToken", "tokenType", "expiresInSeconds" },
  "user": { "id", "name", "email", "role" }
}
```

Protected auth (Bearer required):
- `PUT /api/auth/password` — `{currentPassword, newPassword}`

### Journal

Requires **`ROLE_USER`** or **`ROLE_ADMIN`** (Bearer token):

| Method | Path | Description |
|---|---|---|
| POST | `/api/journals` | Create entry, queues async analysis |
| GET | `/api/journals` | Paginated list (omits sections) |
| GET | `/api/journals/{id}` | Full entry with mood analysis + sections |
| PUT | `/api/journals/{id}` | Update entry, clears + re-queues analysis |
| POST | `/api/journals/{id}/reanalyze` | Force re-run analysis |
| DELETE | `/api/journals/{id}` | Soft delete |
| DELETE | `/api/journals/{id}/permanent` | Hard delete |

**List vs detail:** `GET /journals` omits sections to keep payloads small. Open an entry for full sections.

**Poll for analysis completion:** Poll `GET /api/journals/{id}` until `analysisStatus` is `DONE` or `FAILED`, then call `/api/insights/growth/latest`.

### Growth Insights

| Method | Path | Description |
|---|---|---|
| GET | `/api/insights/growth/latest?entryId=<uuid>` | Post-entry mirror for one journal |

`GET /api/insights/growth/latest` returns:

```json
{
  "entryId": "...",
  "analysisStatus": "DONE",
  "mirrorReady": true,
  "hasTrajectory": true,
  "patternType": "EMOTION_DRIFT_ON_TOPIC_FAMILY",
  "day": {
    "summaryInsight": "...",
    "overallIntensity": 3,
    "dominantMoodLabel": "anxious",
    "themes": ["work", "uncertainty"],
    "copingTip": "...",
    "sections": [{ "topicLabel", "topicFamilyKey", "emotionLabel", "emotionFamilyKey", "intensity", "excerpt" }]
  },
  "trajectory": {
    "kind": "EMOTION_DRIFT_ON_TOPIC_FAMILY",
    "mirrorCard": {
      "headline": "...",
      "trajectoryLine": "...",
      "dayAnchorLine": "...",
      "integratedBody": "...",
      "direction": "GROWTH"
    },
    "trajectoryFacts": {
      "topicFamilyKey": "work_pressure",
      "topicDisplayLabel": "job deadline",
      "priorDominantEmotionFamily": "anxiety",
      "priorDominantAvgIntensity": 3.5,
      "priorDistinctJournalCount": 4,
      "currentDominantEmotionFamily": "acceptance",
      "currentDominantAvgIntensity": 2.1,
      "currentSectionCount": 2
    }
  }
}
```

`trajectoryFacts` exposes the SQL-aggregated numbers behind the mirror narration — the prior emotion family, current emotion family, intensity values, and journal counts. `hasTrajectory` may become `true` slightly after `analysisStatus` becomes `DONE` because the mirror pipeline runs in a separate transaction.

**Timing guidance for clients:** Poll `GET /api/journals/{id}` until `DONE`, then poll `GET /api/insights/growth/latest` every 2–3s up to 8 times.

### Admin

Requires **`ROLE_ADMIN`** (Bearer + `@adminAuth`):

| Method | Path | Description |
|---|---|---|
| PUT | `/api/admin/security/password-pepper` | Rotate server pepper (requires admin password) |
| PUT | `/api/admin/users/{id}/deactivate` | Deactivate user |
| PUT | `/api/admin/users/{id}/password` | Reset user password |

Elevate a user to admin via SQL: `UPDATE users SET role = 'ADMIN' WHERE email = '...';`

---

## Database Schema

### Core Tables

| Table | Key Columns |
|---|---|
| `users` | id, name, email, password_hash, role, is_active |
| `refresh_tokens` | id, user_id, token_hash, expires_at |
| `security_settings` | singleton pepper storage |
| `journal_entries` | id, user_id, title, content, word_count, analysis_status, analysis_attempt/fail_count, in_progress, last_error |
| `mood_analyses` | id, entry_id, mood_label, aggregate_emotion_label_id, intensity, insight, coping_tip, themes[] |
| `journal_entry_sections` | id, entry_id, sort_order, topic_label_id, emotion_label_id, content, intensity |
| `user_topic_labels` | id, user_id, label, normalized_key, family_key, **label_data JSONB** |
| `user_emotion_labels` | id, user_id, label, normalized_key, family_key, **label_data JSONB** |
| `topic_label_embeddings` | id, label_id, user_id, **embedding vector(1024)** |
| `emotion_label_embeddings` | id, label_id, user_id, **embedding vector(1024)** |
| `growth_insights` | id, user_id, insight_type, trigger_entry_id, topic_family, emotion_family, pattern_data JSONB, narration, direction, **pattern_type** |
| `label_backfill_status` | Tracks LLM backfill progress for label_data JSONB |

### label_data JSONB Shape
```json
{"display": "feeling overwhelmed", "category": "emotion", "topic": "stress", "detail": "overwhelm"}
```
Stored with GIN indexes for efficient hierarchy queries. The `display` field is the human-facing label text.

### Flyway Migrations

| Version | Description |
|---|---|
| V1 | users + refresh_tokens |
| V2 | users role CHECK constraint |
| V3 | refresh_token_hash (HMAC replaces plaintext) |
| V4 | journal_entries |
| V5 | mood_analyses |
| V6 | security_settings (singleton pepper) |
| V7 | per_user_labels + topic_sections |
| V8 | family_keys + growth_insights |
| V9 | pattern_type on growth_insights |
| V10 | label_data JSONB on labels (hierarchical storage) |
| V11 | pgvector embedding tables (topic/emotion) |
| V12 | label_backfill_status tracking table |

---

## Project Layout

```
src/main/java/com/cognalytix/source/
├── SourceApplication.java
│
├── analysis/                  # Journal analysis + mirror pipeline
│   ├── JournalAnalysisEventListener.java     # AFTER_COMMIT event consumer
│   ├── JournalAnalysisService.java           # LLM segmentation + section creation
│   ├── AnalysisPrompts.java                  # All LLM prompt templates
│   ├── LlmJournalAnalysisResult.java         # Structured LLM output record
│   ├── SemanticLabelSelector.java            # Embedding-based label selection (service layer)
│   ├── FamilyResolutionService.java          # LLM family clustering for new labels
│   ├── PatternAnalysisService.java          # SQL-aggregate emotion drift detection
│   ├── EmotionDriftFacts.java               # Aggregates-only record for narration
│   ├── PostEntryMirrorService.java          # Orchestrates drift → narration → persist
│   ├── MirrorNarrationService.java          # LLM generates 5-field mirror card
│   ├── MirrorNarrationPrompts.java           # Mirror card prompt templates
│   ├── LlmMirrorCardStructured.java         # Mirror card structured output
│   └── AnalysisErrorCode.java
│
├── config/                   # Framework wiring
│   ├── SecurityConfig.java, JwtAuthenticationFilter.java, JwtService.java
│   ├── AsyncConfig.java                             # analysisExecutor thread pool
│   └── OllamaConfig.java                            # OllamaEmbeddingModel bean
│
├── controller/               # REST layer
│   ├── AuthController.java, UserVocabularyController.java
│   ├── JournalController.java
│   ├── GrowthInsightController.java
│   └── AdminController.java, HealthCheckController.java
│
├── service/                  # Business logic
│   ├── JournalService.java, UserLabelService.java
│   ├── GrowthInsightService.java              # Builds GrowthMirrorResponse DTO
│   ├── EmbeddingStorageService.java           # Async embedding persistence
│   ├── LabelBackfillService.java              # One-time LLM hierarchy backfill
│   └── AuthService.java, JwtService.java, AdminService.java
│
├── dto/                      # API payloads (records)
│   ├── auth/, journal/, insights/, admin/, error/
│   └── insights/: GrowthMirrorResponse, GrowthMirrorCardPayload, GrowthDayMirrorPayload
│
├── domain/                   # JPA entities + Spring Data repositories
│   ├── user/: User.java, UserRepository.java
│   ├── journal/: JournalEntry, MoodAnalysis, JournalEntrySection,
│   │             UserTopicLabel, UserEmotionLabel,
│   │             TopicLabelEmbedding, EmotionLabelEmbedding,
│   │             GrowthInsight, PatternType, GrowthDirection,
│   │             LabelBackfillStatus, *Repository.java
│   ├── token/: RefreshToken.java, RefreshTokenRepository.java
│   └── settings/: SecuritySettings.java, SecuritySettingsRepository.java
│
├── security/                 # Auth components
│   ├── AuthUserPrincipal.java
│   ├── PasswordPepperService.java
│   └── RefreshTokenHasher.java
│
└── exception/                # @RestControllerAdvice
    └── GlobalExceptionHandler.java

src/main/resources/
├── application.yml           # All configuration (no YAML overrides needed)
├── db/migration/V*.sql       # Flyway SQL migrations
└── plans/overview.md        # Product blueprint
```

---

## Roadmap

### Implemented

| Feature | Location |
|---|---|
| Async journal entry analysis | `JournalAnalysisService`, `JournalAnalysisEventListener` |
| Per-user topic/emotion vocabulary | `user_topic_labels`, `user_emotion_labels` |
| Semantic label selection (embeddings) | `SemanticLabelSelector`, `EmbeddingStorageService` |
| Family clustering on new labels | `FamilyResolutionService` |
| Hierarchical label storage (JSONB) | V10 migration, `label_data` on labels |
| Post-entry pattern detection (SQL-first) | `PatternAnalysisService.findPostEntryEmotionDrift()` |
| Structured mirror narration (5-field card) | `MirrorNarrationService`, `LlmMirrorCardStructured` |
| Pattern direction classification | `EmotionDriftFacts.classifyDirection()` |
| Growth insights read model (API) | `GrowthInsightService`, `GrowthInsightController` |
| Trajectory facts explainability | `trajectoryFacts` in `GrowthMirrorResponse` |
| One-time label hierarchy backfill | `LabelBackfillService` |
| Async embedding on new label | `EmbeddingStorageService` |

### Planned (not yet implemented)

| Feature | Notes |
|---|---|
| Weekly insight job | Cron defined in `application.yml`; no `@Scheduled` bean yet |
| Monthly insight job | Cron defined; no bean yet |
| Milestone insight cards | `GrowthInsightType.MILESTONE` defined; no LLM narration yet |
| Cross-topic correlation (14-day) | No `CrossTopicCorrelationService`; `patternData` in `growth_insights` is extensible |
| Scheduled insight endpoints | `GET /api/insights/growth/weekly`, `/monthly`, `/milestones` — no controllers |
| Structured LLM output | All LLM calls use raw text parsing; `BeanOutputParser` not yet used |
| Admin analytics panel | `/api/admin/**` limited to user management; no platform-wide analytics |

---

## Tests

`src/test/resources/application.yml` uses in-memory **H2** (PostgreSQL compat mode), disables Flyway, sets `ANALYSIS_ENABLED=false`, and supplies all required secrets so the app loads without external services.

```bash
./mvnw test
```

---

## Docker Compose

`docker-compose.yml` defines three services:

| Service | Image | Host Port | Notes |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | 5332 → 5432 | pgvector must be installed on the image |
| `ollama` | `ollama/ollama:latest` | 11434 | Pull models before first run |
| `backend` | local Dockerfile | 8000 | Builds from repo root; needs Dockerfile present |

> **Note:** The backend service block in `docker-compose.yml` requires a `Dockerfile` at the repo root. Ensure this exists before `docker compose up backend`.

The **pgvector extension** must be available on the PostgreSQL image. For the Alpine-based `postgres:16-alpine` image, install it via:
```dockerfile
RUN apk add --no-cache postgresql16-pgvector
```
Or use a custom Dockerfile that extends the official PostgreSQL image with the pgvector installation.

---

## Security Model

- Access JWTs are stateless HMAC-signed tokens; no token data in the DB
- Refresh tokens are opaque, HMAC'd before storage, and support rotation
- Passwords: `BCrypt( SHA-256_hex(password + pepper) )` — pepper stored in DB singleton row
- User deactivation: `is_active = false` blocks login and refresh for that account
- Admin routes protected by `@PreAuthorize("hasRole('ADMIN')")` + optional email allowlist

## License

Private project.
