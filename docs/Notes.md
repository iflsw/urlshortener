# Notes for Coding Assessment Tasks

## Overview
This document captures some ideas and thoughts as the work progresses.
This is personal notes only, proper & polished documentation will be provided later in readme. 

## Folder tidy-up
It seems the project was initially defined for a .NET environment.
- .NET specific entries in the .gitignore
- java build artifact folder was not included in .gitignore
Decided to update .gitignore and remove the java build artifact from git, but leave the .NET entries as it does no harm, and will avoid merging those files if they are lefftover from a dev repo later on.

## Local build (outside Docker)
The build needs JDK 17 (the project target). Java 23+ fails: Byte Buddy in Spring Boot 3.2 supports up to Java 22, so Mockito cannot create mocks ("Java 25 (69) is not supported by the current version of Byte Buddy").
With jenv: `jenv local 17` in the repo, plus `jenv enable-plugin export` so Maven sees JAVA_HOME.
Without jenv: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean verify`

`mvn verify` runs unit tests (surefire) and integration tests *IT (failsafe); `mvn test` runs the fast tests only.
Coverage report: backend/target/site/jacoco-merged/index.html

## Build, run and test with docker compose
docker compose up -d --build      # --build is needed after code changes, otherwise the old image is reused
docker compose ps                 # both services "running"
docker compose logs -f api        # wait for "Started UrlShortenerApplication"

If the API behaves like old code after a change: rebuild with --build, and check nothing else is listening on 8080/3000
(`lsof -nP -iTCP:8080 -sTCP:LISTEN`), e.g. a backend started from the IDE.

Persistence:
docker compose down && docker compose up -d keeps the data; docker compose down -v wipes it.

### Where the data lives
- The database is on the named volume api-data (urlshortener_api-data), mounted at /data in the api container.
- It survives rebuilds, restarts and `docker compose down`. Only `docker compose down -v` (or `docker volume rm`) wipes it.
- On a Mac the volume lives inside the Docker Desktop VM, so the file is not visible in Finder/Terminal.
  Copy it out: `docker compose stop api && docker compose cp api:/data/urlshortener.db ./urlshortener-copy.db && docker compose start api`
- A backend run outside Docker (mvn / IDE) uses a different file: ./urlshortener.db relative to the working directory (DATABASE_PATH unset). It is git-ignored (*.db).
- Schema changes to existing tables do not reach an existing volume (CREATE TABLE IF NOT EXISTS); new indexes do (CREATE INDEX IF NOT EXISTS). Wipe the volume or add a migration.

### Confirm existence of data
docker compose exec api ls -l /data
docker volume inspect urlshortener_api-data   # shows the VM-internal path

#### Query in place:
docker run --rm -v urlshortener_api-data:/data alpine sh -c "apk add -q sqlite && sqlite3 /data/urlshortener.db \"SELECT id, alias, created_at FROM shortened_urls ORDER BY id;\""

#### Seeding (use the fixed-width timestamp format, or rows sort by id within the same second):
docker run --rm -v urlshortener_api-data:/data alpine sh -c "apk add -q sqlite && sqlite3 /data/urlshortener.db \"INSERT INTO shortened_urls (alias, full_url, created_at) VALUES ('gh','https://github.com/','2026-10-06T00:00:00.000Z');\""

#### copy db file using docker compose
docker compose cp api:/data/urlshortener.db ~/docker-urlshortener.db


## Exercise the UI and APIs
Swagger: http://localhost:8080/swagger-ui/index.html
UI: open http://localhost:3000 (the URL list UI is still a stub).
API: port 3000 goes through nginx; port 8080 is the API directly.

#### Shorten:
curl -i -X POST localhost:3000/shorten -H 'Content-Type: application/json' -d '{"fullUrl":"https://example.com"}'
-> 201, Location: http://localhost:3000/<7 chars>, JSON body { shortUrl, alias, fullUrl }
With "customAlias":"my-alias" -> 201; taken, invalid (e.g. "café") or reserved ("urls") -> 400 { "error": ... }

#### Redirect: 
curl -i localhost:3000/gh -> 302 with Location: https://github.com/ (curl does not follow it; add -L to follow).
Print only the target: curl -s -o /dev/null -w '%{redirect_url}\n' localhost:3000/gh
Not found: curl -i localhost:3000/nope -> 404

#### Delete: 
curl -i -X DELETE localhost:3000/gh -> 204; then GET /gh -> 404; deleting again -> 404.

#### List (cursor paging, newest first):
curl -i localhost:8080/url
curl -i localhost:3000/urls
curl -s localhost:3000/urls      # shortUrl with alias xyz should be http://localhost:3000/xyz

curl -s 'localhost:3000/urls?size=2'     -> { "items": [...], "nextCursor": "..." }  
curl -s 'localhost:3000/urls?size=2&cursor=<cursor>'  -> next page; nextCursor is null on the last page  

size outside 1-100, size=abc, or a malformed cursor   -> 400 { "error": ... }  
Walk all pages (needs jq):
    cursor=""; while :; do page=$(curl -s "localhost:3000/urls?size=2${cursor:+&cursor=$cursor}"); echo "$page" | jq -r '.items[].alias'; cursor=$(echo "$page" | jq -r '.nextCursor // empty'); [ -z "$cursor" ] && break; done

#### populate a number of entries using shorten
for i in 1 2 3 4 5; do
curl -s -o /dev/null -w "%{http_code} p$i\n" -X POST localhost:3000/shorten \
-H 'Content-Type: application/json' \
-d "{\"fullUrl\":\"https://example.com/$i\",\"customAlias\":\"p$i\"}"
done

#### misc quick tests
- Redirect: curl -i localhost:3000/gh should return 302 with Location: https://github.com/  
- Not found: curl -i localhost:3000/nope should return 404.  
- Delete: curl -i -X DELETE localhost:3000/gh returns 204, but GET /urls still lists gh. (known delete bug: the service inserts and deletes gh-deleted instead of gh)  
- Shorten: curl -i -X POST localhost:3000/shorten -H 'Content-Type: application/json' -d '{"fullUrl":"https://example.com"}' returns 200 with an empty body, and nothing is saved. (known bug: The controller is a stub)  
- Persistence: run docker compose down && docker compose up -d, and the seeded row should still be there. docker compose down -v wipes it.  
- list with cursor (beautified): curl -s 'localhost:3000/urls?size=2' | python3 -m json.tool  
- list with cursor (beautified): curl -s 'localhost:3000/urls?size=2&cursor=PASTE_CURSOR_HERE' | python3 -m json.tool  

#### decode cursor
c=$(curl -s 'localhost:3000/urls?size=2' | jq -r .nextCursor)
python3 -c "import base64,sys; s=sys.argv[1]; print(base64.urlsafe_b64decode(s + '=' * (-len(s) % 4)).decode())" "$c"

## Design decisions

### OpenAPI/Swagger
Added early because it helps. Only needed for the backend, so it is on port 8080 only, not through the frontend.
http://localhost:8080/swagger-ui/index.html

### Database
In this iteration the database is part of the backend container. For a more production-ready setup it should be a separate service.
SQLite allows one writer at a time (database-level write lock, not per table). Fine for this exercise; for higher write concurrency, move to Postgres (row-level locks).

### nginx
In this iteration nginx is part of the frontend container. For a more production-ready solution, consider a separate container.

### Tests and folder architecture
Added JaCoCo and failsafe, and standardised test class names and locations (*Test = unit, *IT = integration). Moved the test classes without code changes, for clarity of intent.
The IT uses an in-memory SQLite database with a single pooled connection (hikari.maximum-pool-size=1): each in-memory connection is its own empty database, so a bigger pool could make tests see different databases.

### Alias generator and URL shortener format
Generated aliases are alphanumeric only (base62, length 7), for simplicity, as some sites don't allow consecutive hyphens or non-alphanumeric characters at the start or end.
Custom aliases also allow the hyphen: ASCII [A-Za-z0-9-], 2-64 characters, same rule as nginx and the frontend. Unicode is rejected (broken links behind nginx, and lookalike/homograph phishing).
Aliases are case-sensitive (Promo != promo): base62 needs it (62^7 vs 36^7 combinations). "urls" and "shorten" are reserved.
Random rather than sequential aliases: not guessable or enumerable. Collisions are negligible (62^7 ≈ 3.5e12) and handled by the atomic insert plus a bounded retry.
The alias generator is a component: separation of concerns and simpler testing (a fixed RandomGenerator can be injected). generateAlias is not static so it can be replaced in tests.
The redirect returns 302 instead of 301 (or 308) because aliases can be deleted, so we don't want browsers to cache the redirect.

### Atomic writes and transactions
The find + save repository methods were replaced by a single saveIfAliasAvailable.
saveIfAliasAvailable uses ON CONFLICT(alias) DO NOTHING, so a duplicate alias isn't an error: the insert is skipped, the row count is 0, the method returns false, and the service turns that into a clean "already taken" (400) for a custom alias or a retry for a generated one. Real errors, such as a NOT NULL violation or a lock timeout, still throw, which is correct because those are genuine faults.
Every write is a single atomic statement (insert, delete), so no @Transactional is needed. The retry loop must not be wrapped in a transaction: it would hold SQLite's write lock across all attempts.
When several statements must succeed or fail together (e.g. the alias registry below), the transaction belongs in the repository method that owns those statements (TransactionTemplate), not around the service's retry loop. @Transactional on a service method is for business operations spanning several repositories; beware the proxy pitfalls (no effect on private methods or self-invocation; rollback only on unchecked exceptions by default).

### Alias deletion (bug fix)
The initial code attempted to mark a deleted alias by copying it with a suffix; the bug was that the copy was deleted, not the original entry.
As a fix, we hard delete the entry. As a result, someone can re-create the same alias and redirect it to a different URL. The README contract doesn't say either way; it leans towards allowing re-creation (a deleted alias "doesn't exist").
DELETE returns 404 for an unknown or already-deleted alias (README contract; same as Rebrandly/Dub). Still idempotent in effect: RFC 9110 allows a different status on repeat.
Options considered to prevent re-use:
- Soft delete with a deleted_at column: reads must filter deleted_at IS NULL; keeps the old URL (audit/undo; privacy concern).
- Ownership: only the owner may update/delete/reclaim an alias. Needs authentication.
- A separate registry of used aliases: the alias is inserted there first, atomically; only if that succeeds is the URL added to the main table. Delete only removes from the main table. Clean main table, no filters, no retained URLs. Preferred future option.
- An update endpoint (PATCH /{alias}) so a link's target can change without delete + re-create.
Not considered:
- Alias prefix/suffix or a magic value (e.g. NULL fullUrl): a separate column/table is cleaner than overloading an existing value's meaning.
- Incremental alias (or part of it): longer and predictable/enumerable, and a custom alias can still take a future value.

Add this to the README:
Deletion - Deletion is permanent (hard delete). A deleted alias can be created again. Risk: links shared before deletion would then redirect to the new target (alias hijacking).
- Future option: a used-alias registry, where aliases are added first (atomically) and never removed. The two inserts must be in one transaction (in the repository method), so a failure to add to the main table reverts the registry insert.
- The registry could be cached.

### Sort
created_at is written as fixed-width UTC milliseconds (yyyy-MM-ddTHH:mm:ss.SSSZ) so text order equals time order; lists are ordered created_at DESC, id DESC (id breaks ties).
Rows written before this change keep the old Instant.toString() format: correct across seconds; within the same second they fall back to id order.

### Paging (cursor / keyset)
GET /urls?size=&cursor= returns { items, nextCursor }; newest first; size 1-100, default 20; nextCursor is null on the last page.
The cursor is base64url of "created_at|id" of the last item on the page. The next query is WHERE created_at < ? OR (created_at = ? AND id < ?), so links created or deleted between requests cause no duplicates or gaps (unlike LIMIT/OFFSET). One extra row is fetched to detect the last page without a COUNT.
The cursor is opaque but not secret: decoding or tampering only moves the position within a list the client can already read.
Trade-offs: no "jump to page N" and no total count. An index on (created_at, id) supports the query.
Envelope chosen over a header (X-Next-Cursor): cleaner contract; it breaks the current frontend list, which is updated in the frontend phase.


