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
| `app.security.password-pepper` | **64-character** secret appended to every password before BCrypt. Override with **`PASSWORD_PEPPER`** in production. |

Database URL and credentials use `spring.datasource.*` with env overrides **`DB_URL`**, **`DB_USER`**, **`DB_PASS`** (see `docker-compose.yml` for the in-network URL `jdbc:postgresql://postgres:5432/cognalytix`).

**Type-safe `app.*` settings:** `JwtProperties` and `AppSecurityProperties` are Java records annotated with `@ConfigurationProperties` and registered via `@EnableConfigurationProperties` on `SourceApplication`. That annotation tells Spring Boot to map keys from `application.yml` (and env vars) onto those types so you get compile-time fields instead of scattering `@Value("${...}")` strings across the codebase.

## Authentication

### Public routes (no access token)

- `POST /api/auth/register` — create account; returns tokens.
- `POST /api/auth/login` — returns tokens.
- `POST /api/auth/refresh` — body `{ "refreshToken": "..." }`; returns a **new access token** (same refresh token).
- `POST /api/auth/logout` — body `{ "refreshToken": "..." }`; marks refresh token **revoked** (idempotent).
- `GET /login`, `GET /error` — reserved for future UI or error pages.
- `GET /actuator/health` — liveness (unauthenticated).

### Protected routes

All other endpoints require a valid **Bearer** access JWT:

```http
Authorization: Bearer <accessToken>
```

Example: `GET /api/health` returns a short string when the token is valid.

### Token response shape

Successful `register` / `login` / `refresh` return JSON like:

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "tokenType": "Bearer",
  "expiresInSeconds": 900
}
```

### Password storage

Passwords are stored with **SHA-256(password + server pepper)** (hex), then **BCrypt** (strength **10**) on that digest. This keeps the pepper, respects BCrypt’s **72-byte input limit**, and allows long passwords.

### Database tables (auth)

Flyway script `V1__users_and_refresh_tokens.sql` creates:

- **`users`** — id, name, email, password hash, role (`USER` \| `ADMIN`), active flag, streak fields, timestamps.
- **`refresh_tokens`** — opaque token string, expiry, revoked flag, FK to `users`.

Deactivate inactive users: login and refresh return **403** when `is_active` is false.

## Docker Compose

`docker-compose.yml` defines **postgres**, **ollama**, and a **backend** service (expects a `Dockerfile` in the repo root when you build the backend image). Postgres is published on host **5332** → container **5432**.

## Tests

`src/test/resources/application.yml` uses an in-memory **H2** database (PostgreSQL compatibility mode), disables Flyway, and supplies test JWT/pepper values so `SourceApplicationTests` can load the context **without** a running Postgres instance.

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
  security/            Spring Security types (e.g. AuthUserPrincipal)
src/main/resources/
  application.yml
  db/migration/        Flyway SQL
  plans/overview.md    Product blueprint
```

This keeps **HTTP**, **application services**, **persistence models**, and **framework wiring** in separate folders so the tree stays easy to navigate as the app grows.

## Roadmap (from blueprint)

Journal CRUD, async Ollama mood analysis, scheduled daily/weekly/monthly insights, React dashboard, Power BI analytics API, and admin endpoints are specified in `overview.md`; this README will evolve as those modules land.

## License

Unspecified in this repository; add a `LICENSE` when you publish the project.
