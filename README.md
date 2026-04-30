# Cognalytix — Backend

Spring Boot API for **Cognalytix**, a journaling and mood-insights product. This repository holds the backend service described in `src/main/resources/plans/overview.md` (locked blueprint, April 2026).

## Stack

| Area | Choice |
|------|--------|
| Runtime | Java **25**, Spring Boot **4.0.x** |
| API | Spring Web MVC, Jakarta Validation |
| Security | JWT access tokens + opaque refresh tokens in PostgreSQL; passwords: **SHA-256(pepper)** then **BCrypt** (strength **10**) |
| Data | Spring Data JPA, PostgreSQL **16**, Flyway migrations |
| AI | Spring AI + Ollama (`qwen3:14b` by default) — **async** journal analysis after create/update/reanalyze; see [docs/journal-analysis-schema.md](docs/journal-analysis-schema.md) |

## Prerequisites

- **Temurin JDK 25** (matches `pom.xml`). If **IntelliJ** shows “cannot find symbol” for types under `com.cognalytix.source.domain.*` while the files exist, set **File → Project Structure → Project** SDK to **25** and **Language level** to **25** (or SDK default), then **Maven** tool window → **Reload** (reimport), and **File → Invalidate Caches → Invalidate and Restart**. The project must be opened at the **folder that contains `pom.xml`**, not a parent or subfolder. Confirm **`./mvnw -q compile`** on the command line uses the same JDK (`java -version` in that terminal should be 25).

## Quick start

1. Start Postgres (example using the repo compose file):

   ```bash
   docker compose up -d postgres
   ```

2. Set secrets for anything beyond local dev (see [Configuration](#configuration)).

3. Run the application:

   ```bash
   ./mvnw spring-boot:run
   ```

   API base URL defaults to `http://localhost:8000` (`server.port`).

## Configuration

Main file: `src/main/resources/application.yml`. Important `app` settings:

| Property | Purpose |
|----------|---------|
| `app.jwt.secret` | HMAC key for signing access JWTs. Base64 is recommended; raw strings shorter than 256 bits are hashed to 32 bytes internally. Override with env **`JWT_SECRET`**. |
| `app.jwt.access-token-expiry-ms` | Access token lifetime (**900000** = **15 minutes**). |
| `app.jwt.refresh-token-expiry-days` | Refresh token lifetime (**7** days). |
| `app.jwt.refresh-storage-secret` | Key material to **HMAC opaque refresh tokens before storing** (plain refresh tokens are never persisted). Set env **`REFRESH_STORAGE_SECRET`** in production. |
| `app.security.password-pepper` | **64-character** secret appended to every password before BCrypt. Override with **`PASSWORD_PEPPER`** in production. |
| `app.security.admin-allowed-emails` | Optional comma-separated emails. Users must already have **`role = ADMIN`** in the database; if this list is **non-empty**, only these emails may call admin APIs. Env **`ADMIN_ALLOWED_EMAILS`**. If **empty**, any **ADMIN** may use admin routes. |
| `app.async.*` | Core / max pool size and queue for **`@Async("analysisExecutor")`** (journal LLM). |
| `app.analysis.enabled` | If **`false`**, async analysis is skipped (no Ollama call). Env **`ANALYSIS_ENABLED`**. |

Database URL and credentials use `spring.datasource.*` with env overrides **`DB_URL`**, **`DB_USER`**, **`DB_PASS`** (see `docker-compose.yml` for the in-network URL `jdbc:postgresql://postgres:5432/cognalytix`).

**Type-safe `app.*` settings:** `JwtProperties` and `AppSecurityProperties` are registered via `@EnableConfigurationProperties` on `SourceApplication`. `AsyncTaskProperties` (`app.async`) is bound in `config/AsyncConfig` for the analysis thread pool. `JwtRefreshConfig` registers `RefreshTokenHasher`. `JacksonObjectMapperConfig` declares an `ObjectMapper` bean (`@ConditionalOnMissingBean`) so filters and JSON entry points serialize **`ApiErrorResponse`** reliably (needed with Boot 4 in tests).

## Errors

Failed requests return **`ApiErrorResponse`** JSON: `status`, `error`, `message`, optional `fieldViolations`, optional `expectedPostRequest` hints for `/api/auth/*` POSTs. JWT failures, unauthorized access (401), and forbidden access (403) use this same envelope.

## Authentication

### Public routes (no access token)

- `POST /api/auth/register` — create account; response includes **`message`**, **`tokens`**, **`user`** (`UserPublicDto`).
- `POST /api/auth/login` — same envelope.
- `POST /api/auth/refresh` — body `{ "refreshToken": "..." }`; **refresh token rotation**: new access + **new** refresh tokens; former refresh hash is revoked.
- `GET /login`, `GET /error` — reserved for future UI or error pages.
- `GET /actuator/health` — liveness (unauthenticated).

### Protected routes

- `POST /api/auth/logout` — requires **`Authorization: Bearer <accessToken>`** and body `{ "refreshToken": "..." }`. Server checks the refresh belongs to that user and revokes it. Response contains **`message`** only (`tokens` / `user` omitted).
- `PUT /api/auth/password` — **`Authorization: Bearer <accessToken>`**; body `{ "currentPassword", "newPassword" }`. Revokes refresh tokens for that user.

Everything else (**including `GET /api/health`** and journaling) requires a valid Bearer access JWT unless listed as public above.

```http
Authorization: Bearer <accessToken>
```

### Successful auth envelope

`register` / `login` / `refresh` return JSON such as:

```json
{
  "message": "Signed in successfully.",
  "tokens": {
    "accessToken": "...",
    "refreshToken": "...",
    "tokenType": "Bearer",
    "expiresInSeconds": 900
  },
  "user": {
    "id": "uuid",
    "name": "Jane",
    "email": "jane@example.com",
    "role": "USER"
  }
}
```

Never returned: password hashes or internal persistence fields (`UserPublicDto` only).

### Password pepper (persistent)

The runtime pepper is **`security_settings.password_pepper`** (Flyway **`V6`**, singleton row **`singleton_key = 1`**). On first startup it is seeded from **`app.security.password-pepper`** (see `SecuritySettingsBootstrap`). `PasswordEncoder` reads the **current DB value via `PasswordPepperService`**, falling back to YAML **only if** that row were missing—normal runs always hit the saved row once bootstrapped.

Admins (**`@adminAuth`**) may rotate pepper:

- **`PUT /api/admin/security/password-pepper`** — body **`{ "adminPassword", "newPepper" }`** (`newPepper` **≥ 32** characters after trim). Uses the **old** pepper to verify **`adminPassword`**, writes the **new** pepper to the DB, **re-keys only this admin user’s bcrypt hash**, calls **`refresh_tokenRepository.deleteAll()`**, and returns a detailed warning: **every other user’s stored hash is still for the old pepper until an admin sets a new password** for them via **`PUT /api/admin/users/{id}/password`**.

### Password storage

Passwords are stored with **SHA-256(password + effective server pepper)** (hex), then **BCrypt** (strength **10**) on that digest.

### Refresh token storage (HMAC + rotation)

Opaque refresh tokens are **HMAC’d** before persistence (**`refresh-storage-secret`** / **`REFRESH_STORAGE_SECRET`**). Flyway **V3** replaces the plaintext `token` column with **`token_hash`**.

### Admin accounts (`users.role`)

There is **no separate admin table**. Set **`role = 'ADMIN'`** in PostgreSQL (`UPDATE users SET role = 'ADMIN' WHERE email = '...';`) for trusted accounts. Optional **`ADMIN_ALLOWED_EMAILS`** restricts **which ADMIN emails** may call **`/api/admin/**`** when the list is non-empty.

**Creating an admin and password:**

1. **Recommended:** Register with `POST /api/auth/register` using the desired email/password, then elevate in SQL:

   ```sql
   UPDATE users SET role = 'ADMIN' WHERE email = 'your@email.com';
   ```

2. **`INSERT`-only databases are awkward** because `password_hash` must be **`BCrypt( SHA-256_hex(plaintext_password + pepper) )`**. Matching the Java stack by hand for ad-hoc rows is brittle. Prefer registering through the API, or use **`PUT /api/admin/users/{id}/password`** after you already have another admin JWT.

Protected admin APIs (Bearer token; **`@adminAuth`**):

- **`PUT /api/admin/security/password-pepper`** — rotate server pepper (**see § Password pepper above**).
- **`PUT /api/admin/users/{id}/deactivate`** — sets `is_active = false`; cannot deactivate self.
- **`PUT /api/admin/users/{id}/password`** — body **`{ "newPassword" }`**; resets that user’s password and clears their refresh-token rows only.

### Journal API

JWT + **`ROLE_USER`** or **`ROLE_ADMIN`**: **`POST /api/journals`**, **`GET /api/journals`** (paginated), **`GET` / **`PUT`** `/api/journals/{id}`**, **`POST /api/journals/{id}/reanalyze`** (rerun LLM), **`DELETE /api/journals/{id}`** (soft delete), **`DELETE /api/journals/{id}/permanent`** (hard delete). Tables: **`journal_entries`**, **`mood_analyses`**, **`journal_entry_sections`**, per-user label tables — see [docs/journal-analysis-schema.md](docs/journal-analysis-schema.md).

Deactivate inactive users: login and refresh return **403** when `is_active` is false.

## Journal AI analysis (Ollama)

**Schema & LLM contract:** [docs/journal-analysis-schema.md](docs/journal-analysis-schema.md) (tables, JSON shape, error codes).

**Config:** `spring.ai.ollama` in `application.yml` — `OLLAMA_BASE_URL` (default `http://localhost:11434`), `OLLAMA_MODEL` (default `qwen3:14b`), `num-predict: 2048` for structured JSON. **Disable** the worker without removing beans: `app.analysis.enabled=false` or env **`ANALYSIS_ENABLED=false`**. Thread pool: `app.async.*` (used by `@Async("analysisExecutor")`).

**Flow:** `POST/PUT /api/journals` and `POST /api/journals/{id}/reanalyze` publish an event **after the DB transaction commits**; the LLM call runs in a background thread, then results are written to `mood_analyses`, `journal_entry_sections`, and per-user `user_topic_labels` / `user_emotion_labels`. Poll `GET /api/journals/{id}` until `analysisStatus` is `DONE` or `FAILED` (see `analysisState` for `lastErrorCode`).

**Prereq:** Ollama running with the model pulled, e.g. `ollama pull qwen3:14b` (or set `OLLAMA_MODEL` to a model you have).

## Docker Compose

`docker-compose.yml` defines **postgres**, **ollama**, and a **backend** service (expects a `Dockerfile` in the repo root when you build the backend image). Postgres is published on host **5332** → container **5432**.

## Tests

`src/test/resources/application.yml` uses an in-memory **H2** database (PostgreSQL compatibility mode), disables Flyway, mirrors `UserDetailsServiceAutoConfiguration` exclusion, sets **`app.analysis.enabled: false`** (no background Ollama in unit tests), and supplies JWT, pepper, **`app.async`**, and **`refresh-storage-secret`** so **`SourceApplicationTests`** loads without Postgres.

```bash
./mvnw test
```

## Project layout (high level)

Packages follow a common **layered** Spring Boot layout under `com.cognalytix.source`:

```
src/main/java/com/cognalytix/source/
  SourceApplication.java
  analysis/            LLM journal analysis: JournalAnalysisService, events, LlmJournalAnalysisResult, prompts, error codes
  config/              Security, JWT, async thread pool, @ConfigurationProperties (JWT, security, async)
  controller/          @RestController layer (auth, health, journals, exports, vocabulary, admin)
  service/             Business logic (auth, journal CRUD, labels, export)
  dto/                 API DTOs (grouped: auth/, journal/, admin/, error/)
  domain/              JPA entities and repositories (user, journal, token, settings)
  security/            Auth principal, entry points, peppering, admin check
  exception/           @RestControllerAdvice
  util/                Shared helpers (e.g. LabelNormalizer)
src/main/resources/
  application.yml
  db/migration/        Flyway SQL
  plans/overview.md    Product blueprint
docs/                  In-repo technical notes (e.g. journal analysis schema)
```

This keeps **HTTP**, **application services**, **persistence models**, and **framework wiring** in separate folders so the tree stays easy to navigate as the app grows.

## Roadmap (from blueprint)

Async mood analysis is **implemented** (see [Journal AI analysis](#journal-ai-analysis-ollama)). Scheduled daily/weekly/monthly insights, React dashboard, and extra Power BI analytics still follow `overview.md`.

## License

Unspecified in this repository; add a `LICENSE` when you publish the project.
