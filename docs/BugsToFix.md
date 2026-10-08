# Bugs to fix

Status after cursor pagination feature implmentation

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

[REQ] Paging. GET /urls?size=&cursor= returns { items, nextCursor } (keyset/cursor paging, newest first, size 1-100, default 20). This is a breaking change to the list response: the frontend must be updated (see TODO).

# NOT FIXED
[OPT] Remove spring.main.allow-bean-definition-overriding=true (a smell) and spring.jpa.generate-ddl (there's no JPA).
NotFixed - leave it for now - Add to Readme



#TODO

[REQ] Frontend: the Vite dev proxy only forwards /shorten and /urls, so DELETE /{alias} and the redirect never reach the API under npm run dev. Not fixed yet (frontend phase). Docker/nginx is unaffected.
[REQ] Frontend: GET /urls now returns { items, nextCursor } instead of an array. Update types/api.ts, services/api.ts and the hook; add a "Load more" control.
[REQ] Frontend: UrlTable is still a stub (its tests already exist and fail).
[REQ] README: replace the task list with the real documentation (API contract including paging, design decisions, security notes).
[OPT] Tests for generated-alias collisions: retry on collision and AliasGenerationException after MAX_ALIAS_ATTEMPTS (mock the repository or inject a fixed RandomGenerator).
[OPT] Exception handling. AliasGenerationException has no handler (returns Spring's default 500 body instead of { "error": ... }). Malformed JSON should also return { "error": ... }. Consider domain exceptions (AliasTakenException, InvalidUrlException, AliasNotFoundException) instead of the generic IllegalArgument/IllegalState mapping, and a catch-all 500 handler that doesn't leak internals.
[OPT] Convert the DTOs to Java records and remove the CompletableFuture noise from the service tests.
[OPT] Pin the JDK: the build needs JDK 17-22 (Byte Buddy in Spring Boot 3.2 does not support Java 23+). Add a maven-enforcer requireJavaVersion rule and commit .java-version (jenv).
[OPT] Reject fullUrls that point at the shortener's own host, to avoid redirect loops.
[OPT] Look at the DB schema again. Schema changes do not reach existing databases (CREATE TABLE IF NOT EXISTS); what would we do with Flyway?

Note: earlier notes mentioned a startup warning caused by log-request-details.  That's not an error and  setting is not in application.properties; if the warning appears, 
check where it is configured.


