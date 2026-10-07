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
