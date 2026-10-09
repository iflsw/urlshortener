# Snip – URL Shortener

A full-stack URL shortener: a **Java 17 / Spring Boot** API with **SQLite**, and a **React + TypeScript** UI served by **nginx**, run together with **Docker Compose**.

- Shorten a long URL, with an optional custom alias
- Follow a short URL (HTTP 302 redirect)
- List short URLs, newest first, with cursor-based paging
- Delete a short URL

Based on the coding exercise [tpximpact/code-exercise-java-incomplete-public](https://github.com/tpximpact/code-exercise-java-incomplete-public).  
The original code was reviewed, fixed and completed; see [Review summary](#review-summary) for what changed.

---

## Contents

- [Quick start (Docker)](#quick-start-docker)
- [Architecture](#architecture)
- [Local development and tools](#local-development-and-tools)
- [Tests](#tests)
- [API reference](#api-reference)
- [Design decisions](#design-decisions)
- [Security](#security)
- [Known limitations and future work](#known-limitations-and-future-work)
- [Review summary](#review-summary)

---

## Quick start (Docker)

Requires Docker with Compose v2.

Available docker compose commands: 
```bash
docker compose up -d --build      # build both images and start them
docker compose ps                 # both services "running"
docker compose logs -f api        # API logs

docker compose down               # stop (keeps data)
docker compose down -v            # stop and wipe the database

docker compose build              # build both images without starting them
```
Tests are not run by Docker Compose; see [Tests](#tests).


| URL | What |
|---|---|
| http://localhost:3000 | The UI, and the API through nginx (same origin) |
| http://localhost:8080 | The backend API, directly |
| http://localhost:8080/swagger-ui/index.html | OpenAPI / Swagger UI |

Data lives in the named volume `api-data` (mounted at `/data` in the `api` container).
It survives a rebuild, a restart, or a `docker compose down`, unless the `-v` option is used.

The database starts empty. To try it out:

```bash
curl -i -X POST localhost:3000/shorten -H 'Content-Type: application/json' \
  -d '{"fullUrl":"https://example.com","customAlias":"demo"}'
curl -i localhost:3000/demo           # 302, Location: https://example.com
curl -s 'localhost:3000/urls?size=5'  # {"items":[...],"nextCursor":null}
```

---

## Architecture

```
browser ──► :3000 nginx (frontend container)
              ├─ /shorten, /urls, /urls?…              ─► api:8080
              ├─ /{alias}  (one segment, 2–64 chars)   ─► api:8080   (redirect, DELETE)
              └─ everything else                       ─► built React app (static files)

api:8080 (Spring Boot) ──► SQLite file on the api-data volume
```

```
backend/             Spring Boot API (controller → service → JDBC repository), SQLite
frontend/            React + TypeScript, built with Vite, served by nginx
docker-compose.yml   Runs both containers
```

- The browser only talks to port 3000, so there are no CORS concerns in Docker.
- nginx keeps the browser's `Host` header, so the API builds short URLs on the address the user actually used (`http://localhost:3000/…`).
- In development, the Vite dev server plays nginx's role with the same routing rules (`frontend/vite.config.ts`).

---

## Local development and tools

### Backend

Requires **JDK 17** (`.java-version` pins it for jenv). On Java 23+ the tests fail, because the Byte Buddy version shipped with Spring Boot 3.2 (used by Mockito to create mocks) only supports up to Java 22.

```bash
cd backend
mvn spring-boot:run                     # API on http://localhost:8080
# without jenv: JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn spring-boot:run
```

Outside Docker the database is `./urlshortener.db` relative to the working directory (override with `DATABASE_PATH`). It is git-ignored.

### Frontend

Requires Node 20+.

```bash
cd frontend
npm ci
npm run dev                             # UI on http://localhost:3000
```

The dev server proxies `/shorten`, `/urls` and alias paths to `http://localhost:8080`, so run the API alongside it (`mvn spring-boot:run`, or `docker compose up -d api` with the `frontend` container stopped, since both use port 3000).

Dependencies are pinned by `package-lock.json`, and the Docker build installs them with `npm ci` (exactly the locked versions). To update them: run `npm outdated` / `npm update` (or `npm install <package>@<version>`), run the tests, and commit `package.json` and `package-lock.json` together.

---

## Tests

Docker Compose does not run the tests. The backend image is built with `-DskipTests`; the frontend image type-checks the code (`tsc`) and builds it, but does not run the Vitest suite.   
Run the tests with the commands below.
 
### Backend

```bash
cd backend
mvn verify     # unit tests (*Test, surefire) + integration tests (*IT, failsafe)
mvn test       # unit tests only
```

Coverage (JaCoCo, unit + integration merged): `backend/target/site/jacoco-merged/index.html`.

- **Unit:** alias generator, service (validation, delete, paging, alias conflicts), repository (timestamp format, ordering, cursor paging), page cursor encoding.
- **Web slice** (`@WebMvcTest`): HTTP contract per endpoint, including error responses.
- **Integration** (`UrlsControllerIT`, full Spring context, in-memory SQLite): end-to-end create → redirect → list → delete, and paging while URLs are created mid-walk.

### Frontend

```bash
cd frontend
npm test                               # Vitest + Testing Library
npx tsc --noEmit && npx tsc -p tsconfig.node.json --noEmit   # type checks
```

Covers components (form, table, pagination), the data hook (paging, delete, shorten result, errors), the API client, URL safety helpers, the app-level success banner behaviour, and the dev-proxy routing rules.

---

## API reference

All errors use one shape:

```json
{ "error": "Human-readable message." }
```

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/shorten` | `201` + `Location` | `400` invalid input, alias taken, malformed JSON; `503` no free alias |
| `GET` | `/urls?size=&cursor=` | `200` | `400` bad `size` or `cursor` |
| `GET` | `/{alias}` | `302` to the long URL | `404` |
| `DELETE` | `/{alias}` | `204` | `404` |

### POST /shorten

```json
// Request
{ "fullUrl": "https://example.com/very/long/path", "customAlias": "my-alias" }

// 201 Created, Location: http://localhost:3000/my-alias
{ "alias": "my-alias", "fullUrl": "https://example.com/very/long/path", "shortUrl": "http://localhost:3000/my-alias" }
```

- `customAlias` is optional. Without it, a random 7-character alias is generated.
- `fullUrl` must be an absolute `http` or `https` URL.
- `400` for: missing or invalid `fullUrl`, malformed alias, reserved alias (`urls`, `shorten`), alias already taken, malformed JSON.
- `503` if no free random alias was found after 5 attempts (practically never; retry).

### GET /urls

Lists URLs **newest first**, one page at a time (cursor/keyset paging).

| Parameter | Default | Notes |
|---|---|---|
| `size` | `20` | 1–100 |
| `cursor` | none | The `nextCursor` from the previous page; omit for the first page |

```json
// 200 OK
{
  "items": [
    { "alias": "my-alias", "fullUrl": "https://example.com/very/long/path", "shortUrl": "http://localhost:3000/my-alias" }
  ],
  "nextCursor": "MjAyNi0xMC0wN1QxMjowMDowMC4wMDBafDQy"
}
```

- `nextCursor` is `null` on the last page.
- Treat the cursor as opaque: send it back unchanged.

### GET /{alias}

`302 Found` with `Location:` set to the long URL, or `404` if the alias does not exist.

### DELETE /{alias}

`204 No Content`, or `404` if the alias does not exist (including a second delete of the same alias).

---

## Design decisions

### Persistence

SQLite via plain JDBC (`JdbcTemplate`):
- The database file path is configurable via the `DATABASE_PATH` environment variable.
- The schema is created at startup with `CREATE TABLE/INDEX IF NOT EXISTS`.

Simple and dependency-free for this scope. See limitations below for what should change for support at scale.

### Aliases

- **Generated:** 7 characters, base62 (`a–z A–Z 0–9`), from `SecureRandom`. That is 62⁷ ≈ 3.5 × 10¹² combinations: not guessable or enumerable, unlike sequential IDs. Collisions are negligible and handled by retrying (max 5).
- **Custom:** ASCII letters, digits and hyphens, 2–64 characters. The same rule is applied by the API, the UI and nginx. Unicode is rejected: it would produce links nginx cannot route, and lookalike (homograph) aliases.
- **Case-sensitive:** `MyAlias` and `myalias` are different aliases.
- **Reserved:** `urls` and `shorten` (API routes) cannot be used as aliases.

### Writes are atomic, without check-then-insert

An alias is claimed with one statement:

```sql
INSERT INTO shortened_urls (alias, full_url, created_at) VALUES (?, ?, ?)
ON CONFLICT(alias) DO NOTHING
```

The `UNIQUE` constraint decides; 0 rows inserted means "taken".
Two concurrent requests can never both get the same alias, and the loser gets a clean `400` instead of a database error.
Every write is a single statement, so no `@Transactional` is needed.
Note: where several statements must succeed or fail together in future (see the alias registry below), the transaction belongs in the repository method that owns them.

### Errors and Message Banners

- One JSON shape, `{ "error": "..." }`, for every failure: validation, conflicts, malformed JSON, bad paging parameters, and a catch-all `500` with a generic message (details are logged server-side only).
- In the UI:
  - URL and alias validation errors appear within the form; no request is sent. API errors (e.g. "alias taken", a failed delete) appear in an error banner.
  - The form's validation message is replaced on the next submit. The error banner is cleared when a submission reaches the API (and replaced if the API rejects it), and on Previous/Next or delete.
  - A failed submission keeps what the user typed; a successful one clears the form.
  - The success banner (the new short URL) is removed on every press of *Shorten URL*, including when validation then fails, but stays while the user types, so the previous short URL can still be copied.

### UI

- The list shows 10 URLs per page (the UI sends `size=10`; the API default is 20).
- Deleting asks for confirmation, since links using the alias stop working.

### Redirect: 302, not 301

A 301 is cached by browsers indefinitely. Because short URLs can be deleted, a cached 301 would keep redirecting after deletion; 302 always asks the server.

### Delete: hard delete

Deleting removes the row, and the alias can be created again. Repeat deletes return `404`, which keeps `DELETE` idempotent in effect (the alias stays gone) as HTTP allows.

Trade-off: re-creating a deleted alias makes previously shared links point at the new target ("alias hijacking"). See [future work](#known-limitations-and-future-work).

### Sorting and paging

- `created_at` is stored as fixed-width UTC milliseconds (`yyyy-MM-ddTHH:mm:ss.SSSZ`), so text order equals time order. Lists are ordered `created_at DESC, id DESC`; `id` breaks ties.
- Paging uses a **cursor** (keyset) rather than `LIMIT/OFFSET`: the cursor holds the `(created_at, id)` of the last item seen, and the next page is "everything older than that". URLs created or deleted between requests therefore never cause duplicates or gaps, and the query stays fast on deep pages (index on `(created_at, id)`).
- The cursor is base64url-encoded so clients treat it as opaque. It is not a secret: decoding it only reveals a position in a list the client can already read.
- Trade-offs: no "jump to page N" and no total count. The UI offers Previous/Next (it keeps a stack of cursors) and shows "Page N".

### Testing

See also  [Tests](#tests).

- The frontend includes React component tests and service-level API tests.
- The backend includes service-level tests and integration tests; A Jacoco test coverage is also available.
- Backend Test structure and naming were standardised: test classes mirror the package of the class under test, unit tests end in `*Test` and integration tests in `*IT`. `mvn test` runs only the fast unit tests, `mvn verify` adds the integration tests, and coverage is reported for each and merged. 

---

## Security

**Implemented**

- **Only `http`/`https` URLs** are accepted (API and UI). `javascript:`, `data:`, `file:` and similar schemes are rejected, so a short link cannot run script or serve an attacker's page.
- **Safe rendering:** the UI renders stored URLs as links only if they are `http(s)` (React does not block `javascript:` hrefs).
- **Unguessable aliases** (`SecureRandom`).
- **Parameterised SQL** everywhere, including the paging query.
- **No internals in error responses** (generic `500` message).
- **Containers:** the API runs as a non-root user; frontend dependencies are installed from the lockfile (`npm ci`) for reproducible builds.

**Not implemented (documented)**

- **No authentication or ownership:** anyone can create or delete any short URL. This is the largest gap for real use.
- **No rate limiting:** add it at nginx or an API gateway.
- **Abuse:** any shortener can hide phishing or malware links. Mitigations: URL reputation checks, a preview page before redirecting.
- **Swagger UI and port 8080** are exposed for convenience; disable/remove them in production.
- **CORS** origins are hard-coded for local development; make them configuration.
- **Dependency auditing** is not part of the image build; use `npm audit` / Dependabot.

---

## Known limitations and future work

| Area | Limitation | Option |
|---|---|---|
| Alias reuse | A deleted alias can be re-created, redirecting old links elsewhere | A "used aliases" registry: insert the alias there first (atomically, in the same transaction as the URL); never delete from it. Or ownership via authentication |
| Changing a target | Only delete + re-create | `PATCH /{alias}` to update the long URL |
| Concurrency | SQLite allows one writer at a time | WAL mode + busy timeout, or PostgreSQL for real write load |
| Schema changes | `CREATE TABLE IF NOT EXISTS` does not migrate existing databases | Flyway or Liquibase migrations |
| Older rows | Rows created before the fixed-width timestamp format sort by `id` within the same second | One-off migration to the new format |
| Corrupt stored URL | A stored URL that is not a valid URI would return `400` on redirect | Domain-specific exceptions so only client errors map to `400` |
| Error display | Error messages are not dismissed while the user edits the form; they are replaced on the next submit | Clear them on edit (considered; not needed for this scope) |
| Self-referencing URLs | A long URL may point back at the shortener itself | Reject the shortener's own host |
| Seed data | No seed data is provided; the database starts empty | A `data.sql` or startup seeder if needed |
| Configuration | `spring.main.allow-bean-definition-overriding` and `spring.jpa.generate-ddl` are left over in `application.properties` | Remove (no JPA is used) |
| Parametrisation | Values such as alias length and page sizes are hard-coded constants | Make them configurable in the properties files |

---

## Review summary

The original solution was reviewed, fixed and completed. Main findings:

| Finding | Fix |
|---|---|
| The brief states the database is pre-seeded, but no database file or seed script was in the repository | Documented; the database starts empty (see Quick start for creating sample data) |
| `POST /shorten` was a stub (200, empty body, nothing saved) | Implemented: `201`, JSON body, `Location` header |
| `generateAlias` not implemented | `SecureRandom` base62 generator, bounded retry |
| Delete inserted and removed a copy (`<alias>-deleted`); the original was never removed, so it reappeared on refresh | Delete the alias itself, in one statement |
| Custom alias check-then-insert race (could return `500`) | Atomic `INSERT … ON CONFLICT DO NOTHING` |
| Alias validation accepted Unicode letters/digits, unlike nginx and the UI | ASCII-only rule everywhere; reserved route names |
| No sorting; `Instant.toString()` timestamps sorted wrongly within a second | Fixed-width timestamps, newest first |
| No paging | Cursor paging, `{ items, nextCursor }` |
| URL list UI was a stub | Table with delete, Previous/Next |
| UI form cleared even when the API rejected the request, and accepted `javascript:` URLs | Keeps input on failure; http(s) only |
| The success banner of a previous submission stayed visible when the next submission failed validation | Cleared on every submit attempt |
| Dev server did not proxy alias paths (delete/redirect broken under `npm run dev`) and rewrote the `Host` header | Proxy rules aligned with nginx |
| Some errors returned Spring's default body instead of `{ "error": ... }` | Handlers for malformed JSON, alias exhaustion, and a catch-all |
| Integration tests used an in-memory SQLite DB behind a connection pool (each connection is a separate database) | Single pooled connection in tests |
| Docker: build artifacts committed; no Compose file; `npm install` without lockfile | `.gitignore`/`.dockerignore`, `docker-compose.yml`, `npm ci` |
