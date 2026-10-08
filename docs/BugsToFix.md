# Bugs to fix

# FIXED
[OPT] Remove the committed backend/target/ folder and fix .gitignore, which still has .NET leftovers (bin/, obj/, c-sharp) and is missing target/.
done
[REQ] Delete bug. delete() inserts alias-deleted and then deletes that row. The original is never removed, so the UI optimistically hides it and it reappears on refresh. It can also throw a UNIQUE violation (500) on a second delete. The fix is to call deleteByAlias(alias) directly.
Tests: deleting an existing alias makes a subsequent GET return 404; deleting an unknown alias returns 404; deleting twice returns 404 the second time.
[REQ] Delete in dev mode. The Vite proxy only forwards /shorten and /urls, so DELETE /{alias} never reaches the API under npm run dev. This may be part of the "strange behaviour".
[REQ] generateAlias. Use SecureRandom with base62 and length 7, as the existing constants suggest. On collision, retry a bounded number of times, then fail cleanly. ThreadLocalRandom is predictable, so don't use it; it's currently an unused import.
T
Tests: output matches the alias regex and length; collisions trigger a retry (mock the repository or inject a RandomGenerator); exhausted retries produce a clear error.

[REQ] Hidden bug: POST /shorten is a stub. It returns 200 with no body and never calls the service. The README contract requires 201, a JSON body, and ideally a Location header. Write a controller test first.
REQ] Race condition. The existsByAlias then save sequence is a time-of-check/time-of-use race. Catch DuplicateKeyException from the UNIQUE constraint and map it to the "alias taken" error rather than a 500.
[REQ] Alias validation mismatch. Character.isLetterOrDigit accepts Unicode (é, Arabic digits), while the frontend and nginx only allow [a-zA-Z0-9-]. Switch to an ASCII regex.
[OPT] Reserved aliases. Block urls and shorten; otherwise a custom alias urls can be created but never redirects.



# NOT FIXED
[OPT] Remove spring.main.allow-bean-definition-overriding=true (a smell) and spring.jpa.generate-ddl (there's no JPA).
NotFixed - leave it for now - Add to Readme



#TODO
[OPT] Exception handling. Replace the generic IllegalArgument/IllegalState mapping with domain exceptions (AliasTakenException, InvalidUrlException, AliasNotFoundException). Add a catch-all 500 handler that doesn't leak internals, and make malformed JSON also return { "error": ... }.
[OPT] Convert the DTOs to Java records and remove the CompletableFuture noise from the tests.

Spring Boot logs a warning at startup because of log-request-details. The warning says request details may contain sensitive data. That's expected, not an error

[OPT] Look at db schema again. is it the right format. what would we do if using flyway. 