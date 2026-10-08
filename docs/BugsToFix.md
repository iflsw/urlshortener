# Bugs to fix

Status after the frontend URL list and paging (2026-10-08)

# FIXED

[OPT] Remove the committed backend/target/ folder and fix .gitignore, which still had .NET leftovers (bin/, obj/, c-sharp) and was missing target/.
Done. The .NET entries were left in place on purpose (harmless).

[REQ] generateAlias. SecureRandom, base62, length 7 (RandomAliasGenerator, injectable RandomGenerator). On collision the service retries up to MAX_ALIAS_ATTEMPTS (5) times, then throws AliasGenerationException.
Tests: output length and alphabet, uniform index requests, distinct aliases, null random rejected.
Gap: no test yet for the retry-on-collision path or for exhausted retries (see TODO).

[REQ] Hidden bug: POST /shorten was a stub (200, no body, never called the service).
Fixed: returns 201 with the JSON body and a Location header pointing at the short URL. Covered by a @WebMvcTest slice (UrlsControllerTest) and end-to-end tests (UrlsControllerIT).

[REQ] Race condition on custom aliases. The existsByAlias-then-save sequence was a time-of-check/time-of-use race: the loser of a race got a UNIQUE violation and a 500.
Fixed with an atomic insert, not by catching DuplicateKeyException: saveIfAliasAvailable uses INSERT ... ON CONFLICT(alias) DO NOTHING, so the database decides; 0 rows inserted means "alias taken" (400). Custom and generated aliases now share this one insert path. existsByAlias and save were removed.

[REQ] Delete bug. delete() inserted "<alias>-deleted" and then deleted that row, so the original was never removed (it reappeared on refresh), and a second delete could hit a UNIQUE violation (500).
Fixed: delete() calls deleteByAlias(alias) directly (one atomic DELETE). Hard delete; see Notes.md for the re-creation trade-off.
Tests: deleting makes GET return 404 and removes it from the list; unknown alias returns 404; deleting twice returns 404 the second time; other aliases are untouched.

[REQ] Alias validation mismatch. Character.isLetterOrDigit accepted Unicode (é, Arabic-Indic digits, full-width letters), while the frontend and nginx only allow [a-zA-Z0-9-].
Fixed: ASCII regex [A-Za-z0-9-]{2,64} (Pattern.matches, so no anchor pitfalls).

[OPT] Reserved aliases. "urls" and "shorten" are rejected (400), since an alias with an API route's name could never redirect. Exact match: aliases are case-sensitive, so "URLS" is allowed.

[REQ] Sort by creation date. created_at was written with Instant.toString(), which drops zero fractions, so text ordering was wrong within a second ("...:00Z" sorts after "...:00.500Z").
Fixed: created_at is written as fixed-width UTC milliseconds (yyyy-MM-ddTHH:mm:ss.SSSZ); lists are ordered created_at DESC, id DESC (id breaks ties).

[REQ] Paging. GET /urls?size=&cursor= returns { items, nextCursor } (keyset/cursor paging, newest first, size 1-100, default 20). This is a breaking change to the list response; the frontend was updated (below).

[REQ] Frontend: UrlTable was a stub ("TODO: Display shortened URLs here."; its 3 tests failed).
Fixed: table with columnheaders, one row per URL, Delete button per row labelled with its alias, links open in a new tab. The 3 original tests pass, plus new ones.

[REQ] Frontend: list response changed from an array to { items, nextCursor } for pagination support
Fixed: api.listPage replaces listAll, sends size=10 and the cursor, and validates the envelope. Types updated (UrlPage).

[REQ] Frontend paging UI. Previous/Next with "Page X" (no total from the API), using a client-side cursor stack; refetch after delete, step back when a page empties, back to page 1 after shorten or on a rejected cursor, stale responses ignored. See Notes.md.
Tests: Pagination component, paging hook, api service.

[REQ] Frontend security: stored URLs were going to be rendered as links. React does not block javascript: hrefs.
Fixed: only http/https URLs become links (isHttpUrl); others are plain text. External links use rel="noopener noreferrer".

[OPT] Frontend: confirmation before delete; rows stay visible while the next page loads; the per-page count badge was removed (it only counted the current page).

# NOT FIXED
[OPT] Remove spring.main.allow-bean-definition-overriding=true (a smell) and spring.jpa.generate-ddl (there's no JPA).
NotFixed - leave it for now - Add to Readme



#TODO

[REQ] Frontend: the Vite dev proxy only forwards /shorten and /urls, so DELETE /{alias} and the redirect never reach the API under npm run dev. Not fixed yet (frontend phase). Docker/nginx is unaffected.
[REQ] Frontend: the shorten form clears even when the API rejects the request (e.g. "alias taken"), because the hook catches the error and resolves. Return success/failure (or rethrow) and only clear on success. Test first.
[REQ] Frontend: URL validation in ShortenForm uses new URL(), which accepts javascript: and ftp:. Restrict to http(s), mirroring the backend.
[REQ] README: replace the task list with the real documentation (API contract including paging, design decisions, security notes).
[OPT] Tests for generated-alias collisions: retry on collision and AliasGenerationException after MAX_ALIAS_ATTEMPTS (mock the repository or inject a fixed RandomGenerator).
[OPT] Exception handling. AliasGenerationException has no handler (returns Spring's default 500 body instead of { "error": ... }). Malformed JSON should also return { "error": ... }. Consider domain exceptions (AliasTakenException, InvalidUrlException, AliasNotFoundException) instead of the generic IllegalArgument/IllegalState mapping, and a catch-all 500 handler that doesn't leak internals.
[OPT] Convert the DTOs to Java records and remove the CompletableFuture noise from the service tests.
[OPT] Pin the JDK: the build needs JDK 17-22 (Byte Buddy in Spring Boot 3.2 does not support Java 23+). Add a maven-enforcer requireJavaVersion rule and commit .java-version (jenv).
[OPT] Reject fullUrls that point at the shortener's own host, to avoid redirect loops.
[OPT] Frontend: ShortenForm tests print "not wrapped in act(...)" warnings (userEvent used without setup()). Use userEvent.setup().
[OPT] Frontend: copy to clipboard in ResultBanner can reject (no secure context, permission); the rejection is not handled.
[OPT] Frontend: browser Back leaves the app instead of going to the previous page; refresh returns to page 1. Could push a history entry per page (history.pushState) and/or keep the cursor stack in sessionStorage.
[OPT] Frontend: a "First page" button or links to already visited pages (both possible from the cursor stack; unvisited pages cannot be reached with cursors).
[OPT] Frontend: self-host the Google Fonts (no third-party request, simpler CSP); npm audit; ESLint with jsx-a11y.
[OPT] Show the creation date (needs createdAt on UrlListItem). Not required: the README asks for sorting by creation date, not displaying it.
[OPT] Backend IT: fetch page 1, delete the row its nextCursor points at, fetch page 2 with that cursor: expect 200 and the same rows (proves cursor paging survives deletes).
[OPT] Legacy created_at rows (Instant.toString format) can be misordered within the same second; a one-off UPDATE to the fixed-width format would fix it. Seed data format unknown.
[OPT] shortUrl and Location are built from the request Host header (Host header injection, low risk: reflected to the caller only, not stored). Use a configured base URL (e.g. APP_BASE_URL) or server.forward-headers-strategy with trusted proxies.
[OPT] Redirect uses URI.create(fullUrl): a malformed stored row would give a 500 with Spring's default body (covered by the catch-all handler TODO above).
[OPT] docker-compose: add an api healthcheck and depends_on: condition: service_healthy.
[OPT] Look at the DB schema again. Schema changes do not reach existing databases (CREATE TABLE IF NOT EXISTS); what would we do with Flyway?

Note: earlier notes mentioned a startup warning caused by log-request-details.  That's not an error and  setting is not in application.properties; if the warning appears, 
check where it is configured.

# README ONLY (document, no code change planned)
- No authentication: anyone can create or delete any URL. No rate limiting.
- Shortened links can hide phishing or malware targets (no URL reputation check).
- Security headers (CSP, X-Content-Type-Options, Referrer-Policy, frame-ancestors) belong in nginx.conf; the Docker files were treated as fixed.
- Swagger and the API port 8080 are exposed for testing; disable Swagger by profile and stop publishing 8080 in production.
- Cursor paging trade-offs: no total, no jump to page N, no deep links; one row can repeat when going back after deletes.
- README API Reference is out of date: GET /urls now takes size and cursor and returns { items, nextCursor }.
