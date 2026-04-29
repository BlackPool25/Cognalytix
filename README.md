# Cognalytix — Backend

Spring Boot API for **Cognalytix**, a journaling and mood-insights product. This repository holds the backend service described in `src/main/resources/plans/overview.md` (locked blueprint, April 2026).

## Stack

| Area | Choice |
|------|--------|
| Runtime | Java **25**, Spring Boot **4.0.x** |
| API | Spring Web MVC, Jakarta Validation |
| Security | JWT access tokens + opaque refresh tokens in PostgreSQL; passwords: **SHA-256(pepper)** then **BCrypt** (strength **10**) |
| Data | Spring Data JPA, PostgreSQL **16**, Flyway migrations |
| AI (planned) | Spring AI + Ollama (`qwen3:14b` by default) |

## Prerequisites

- **JDK 25** (matches `pom.xml`; the project will not compile on older bytecode targets without changing `<java.version>`).
- **PostgreSQL** (local or Docker). Default JDBC URL targets host port **5332** (see `docker-compose.yml` and the plan in `overview.md`).
- **Maven** (wrapper `./mvnw` is included).

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

Database URL and credentials use `spring.datasource.*` with env overrides **`DB_URL`**, **`DB_USER`**, **`DB_PASS`** (see `docker-compose.yml` for the in-network URL `jdbc:postgresql://postgres:5432/cognalytix`).

**Type-safe `app.*` settings:** `JwtProperties` and `AppSecurityProperties` are Java records annotated with `@ConfigurationProperties` and registered via `@EnableConfigurationProperties` on `SourceApplication`. `JwtRefreshConfig` registers `RefreshTokenHasher`. `JacksonObjectMapperConfig` declares an `ObjectMapper` bean (`@ConditionalOnMissingBean`) so filters and JSON entry points serialize **`ApiErrorResponse`** reliably (needed with Boot 4 in tests).

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

JWT + **`ROLE_USER`** or **`ROLE_ADMIN`**: **`POST /api/journals`**, **`GET /api/journals`** (paginated), **`GET` / **`PUT`** `/api/journals/{id}`**, **`DELETE /api/journals/{id}`** (soft delete), **`DELETE /api/journals/{id}/permanent`** (hard delete). Entries use Flyway tables **`journal_entries`** (V4) and **`mood_analyses`** (V5).

Deactivate inactive users: login and refresh return **403** when `is_active` is false.

## Docker Compose

`docker-compose.yml` defines **postgres**, **ollama**, and a **backend** service (expects a `Dockerfile` in the repo root when you build the backend image). Postgres is published on host **5332** → container **5432**.

## Tests

`src/test/resources/application.yml` uses an in-memory **H2** database (PostgreSQL compatibility mode), disables Flyway, mirrors `UserDetailsServiceAutoConfiguration` exclusion, and supplies JWT, pepper, and **`refresh-storage-secret`** values so **`SourceApplicationTests`** loads without Postgres.

```bash
./mvnw test
```

## Project layout (high level)

Packages follow a common **layered** Spring Boot layout under `com.cognalytix.source`:

```
src/main/java/com/cognalytix/source/
  SourceApplication.java
  config/              Security, JWT filter, password encoder, @ConfigurationProperties beans
  controller/          @RestController HTTP layer (auth, health, …)
  service/             Business logic (e.g. AuthService, JwtService)
  dto/                 Request/response records for APIs
  domain/
    user/              JPA user model + UserRepository
    token/             Refresh token entity + RefreshTokenRepository
    journal/           Journal entries + mood analysis entities & repositories
    settings/          Security singleton (password pepper persistence)
  security/            Spring Security types (e.g. AuthUserPrincipal)
src/main/resources/
  application.yml
  db/migration/        Flyway SQL
  plans/overview.md    Product blueprint
```

This keeps **HTTP**, **application services**, **persistence models**, and **framework wiring** in separate folders so the tree stays easy to navigate as the app grows.

## Roadmap (from blueprint)

Async mood analysis (**Spring AI / Ollama**), scheduled daily/weekly/monthly insights, React dashboard, and Power BI analytics endpoints continue in `overview.md`; build them on top of journaling and auth shipped here first.

## License

Unspecified in this repository; add a `LICENSE` when you publish the project.
