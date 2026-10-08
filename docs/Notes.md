# Notes for Coding Assessment Tasks

## Overview
This document captures some ideas and thoughts as the work progresses -  
This is personal notes only, proper & polished documentation will be provided later in readme. 

## Folder tidyup
It seems the project was initially defined for a .NET environment.
- .NET specific entries in the .gitignore
- java build artifact folder was not included in .gitignore
Decided to update .gitignore and remove the java build artifact from git, but leave the .NET entries as it does no harm, and will avoid merging those files if they are lefftover from a dev repo later on.

## Build, run and test with docker compose
docker compose build
docker compose up -d
docker compose ps                 # both services "running"
docker compose logs -f api        # wait for "Started UrlShortenerApplication"

### confirm existence of data:
docker compose exec api ls -l /data
docker volume inspect urlshortener_api-data   # shows the VM-internal path
seeding:

docker run --rm -v urlshortener_api-data:/data alpine sh -c "apk add -q sqlite && sqlite3 /data/urlshortener.db \"INSERT INTO shortened_urls (alias, full_url, created_at) VALUES ('gh','https://github.com/','2026-10-06T00:00:00Z');\""

### exercise UI and apis

UI: open http://localhost:3000.
API: 
    curl -i localhost:8080/url
    curl -i localhost:3000/urls


curl -s localhost:3000/urls      # shortUrl should be http://localhost:3000/gh

Redirect: curl -i localhost:3000/gh should return 302 with Location: https://github.com/. Opening http://localhost:3000/gh in a browser should land on GitHub.
Not found: curl -i localhost:3000/nope should return 404.
Delete: curl -i -X DELETE localhost:3000/gh returns 204, but GET /urls still lists gh. (known delete bug: the service inserts and deletes gh-deleted instead of gh)
Shorten: curl -i -X POST localhost:3000/shorten -H 'Content-Type: application/json' -d '{"fullUrl":"https://example.com"}' returns 200 with an empty body, and nothing is saved. (known bug: The controller is a stub)
Persistence: run docker compose down && docker compose up -d, and the seeded row should still be there. docker compose down -v wipes it.


## design decisions
### OPenAI/Swagger
We add swagger early, because it helps - we only need in th ebackedn, so we add it to port 8080 only, not available from frontend.
http://localhost:8080/swagger-ui/index.html


### Database
In this iteration, the database is part of the backend - For a more production ready db, it should be a separate container.

### nginx 
In this iteration, nginx is part of the frontend  - For a more production ready solution, consider a separate container. 

### Tests and Folder architecture
Added jacoco and failsafe, and standardised test classes names and location - Moved the test classes without code changes for clarity of intent.
Note that for full testingwe need torun mvn verify (AS mvn test would run only the fast tests and skip the IT tests)

### alias generator and Url shortener format
the random geneated shortener will be alphanumeric characters only, for simplicity - as some site would not allow consecutive minus characters or non alphanumeric atthe start or theend.
the usuer generated alias will allow for hyphen (minus) character as well
We make the alias generator a component - this enforces separation of concerns and allow simpler testing. 
The method generateAlias in the service class cannot be static: it would prevent replacing with tesrt code - also we want to generate and save in the db in one transactional go. 
The redirect call returns 302 instead of 301 (or 308) because in this app it is possible to delete an alias, so we dont want the redirect to be cached by a browser

### alias deletion (bug fix)
The initial code attempts tp mark a deleted allias by appending a suffix - the bug was that the row with the suffix was deleted, but not the original entry.
AS a fix, we hard delete the entry. AS a result, it would potentially be possible for someone to recreate the same alias and redirect to a different url
- A solution would be to soft delete, by adding a deleted column (in which case the creation and redirect would check that any existing alias would have this noumn null)
- Another option would be to add some sort of security, only the owner of a url would be allowed to update/delete it.
- another solid option would be to keep all used aliases in a separate table - first the alias is stored there in an atomic operation and only if successful is it added to the right table. delete just delete from the main tablr
- Not considered 
- alias prefix or suffix or magic url value - a separate column is cleaner than overloading existing values meaning
- incremental alias (or part of alias) - makes the alias longer or more predictable - can still be taken by custom alias
Add this to readme:
Deletion - Deletion is permanent (hard delete). A deleted alias can be created again. Risk: links shared before deletion would then redirect to the new target (alias hijacking). 
- Future options: a separate used alias registry, where previous aliases are added first (atomically) and never removed. note that the add wouldneed to be @transactional s that failure to add in the main table would revert the add in the registry. 
- The regitry could be cached. 
- consider adding @transactional to service methods as we may have more than one sql operation if we want to make the delete more clever. 
- 


