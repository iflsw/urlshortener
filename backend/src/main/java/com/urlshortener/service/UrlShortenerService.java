package com.urlshortener.service;

import com.urlshortener.exception.AliasGenerationException;
import com.urlshortener.model.ShortenUrlRequest;
import com.urlshortener.model.ShortenUrlResponse;
import com.urlshortener.model.UrlPage;
import com.urlshortener.repository.PageCursor;
import com.urlshortener.repository.ShortenedUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;


@Service
public class UrlShortenerService {
    private static final Logger log = LoggerFactory.getLogger(UrlShortenerService.class);
    static final int MAX_ALIAS_ATTEMPTS = 5;
    static final int MAX_PAGE_SIZE = 100;

    /**
     * ASCII letters, digits and hyphens only, 2-64 characters. Deliberately not Character.isLetterOrDigit, which accepts any
     * Unicode letter or digit (e.g. "café", Arabic-Indic digits, full-width letters).
     */
    private static final Pattern ALIAS_PATTERN = Pattern.compile("[A-Za-z0-9-]{2,64}");

    /** Paths used by API endpoints: an alias with one of these names could never redirect. */
    private static final Set<String> RESERVED_ALIASES = Set.of("urls", "shorten");

    private final ShortenedUrlRepository repository;
    private final AliasGenerator aliasGenerator;

    public UrlShortenerService(ShortenedUrlRepository repository, AliasGenerator aliasGenerator) {
        this.repository = repository;
        this.aliasGenerator = aliasGenerator;
    }

    public ShortenUrlResponse shorten(ShortenUrlRequest request, String baseUrl) {
        var fullUrl = normalizeUrl(request.getFullUrl());
        var customAlias = request.getCustomAlias();
        var alias = customAlias == null || customAlias.isBlank()
                ? saveWithGeneratedAlias(fullUrl)
                : saveWithCustomAlias(customAlias.trim(), fullUrl);

        return new ShortenUrlResponse(baseUrl + "/" + alias, alias, fullUrl);
    }

    private String saveWithCustomAlias(String alias, String fullUrl) {
        if (!isValidAlias(alias)) {
            throw new IllegalArgumentException("Alias may only contain letters, numbers, and hyphens (2–64 characters).");
        }
        if (RESERVED_ALIASES.contains(alias)) {
            throw new IllegalArgumentException("The alias '" + alias + "' is reserved.");
        }

        // Atomic: the UNIQUE constraint decides, so two concurrent requests can never both claim
        // the alias, and the loser gets a clean "taken" error rather than a 500.
        if (!repository.saveIfAliasAvailable(alias, fullUrl, Instant.now())) {
            throw new IllegalStateException("The alias '" + alias + "' is already taken.");
        }
        return alias;
    }

    private String saveWithGeneratedAlias(String fullUrl) {
        for (int attempt = 1; attempt <= MAX_ALIAS_ATTEMPTS; attempt++) {
            var alias = aliasGenerator.generate();
            if (repository.saveIfAliasAvailable(alias, fullUrl, Instant.now())) {
                return alias;
            }
            // Frequent warnings here mean the alias space is getting crowded - increase the length of the alias string
            log.warn("Generated alias collided with a pre-existing one (attempt {} of {})", attempt, MAX_ALIAS_ATTEMPTS);
        }
        throw new AliasGenerationException(MAX_ALIAS_ATTEMPTS);
    }

    public String getFullUrl(String alias) {
        return repository.findFullUrlByAlias(alias).orElse(null);
    }

    /**
     * One page of URLs, newest first.
     *
     * @param cursor the nextCursor from the previous page, or null/blank for the first page
     * @param size   number of items per page, 1 to {@value #MAX_PAGE_SIZE}
     * @throws IllegalArgumentException if size is out of range or the cursor is malformed
     */
    public UrlPage getPage(String baseUrl, String cursor, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        var after = (cursor == null || cursor.isBlank()) ? null : PageCursor.decode(cursor.trim());
        return repository.findPage(baseUrl, after, size);
    }

    /**
     * Deletes the mapping for the given alias.
     * A single DELETE statement: atomic, with no find-then-delete race.
     * @return true if a mapping was deleted, false if the alias did not exist
     */
    public boolean delete(String alias) {
        return repository.deleteByAlias(alias);
    }

    private static boolean isValidAlias(String alias) {
        // matches() requires the whole string to match, so no ^/$ anchors are needed.
        return alias != null && ALIAS_PATTERN.matcher(alias).matches();
    }

    private static String normalizeUrl(String fullUrl) {
        if (fullUrl == null || fullUrl.isBlank()) {
            throw new IllegalArgumentException("fullUrl is required.");
        }

        try {
            var uri = new URI(fullUrl.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("fullUrl must be a valid URL.");
            }

            var scheme = uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("fullUrl must be a valid URL.");
            }

            var path = uri.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }

            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    path,
                    uri.getQuery(),
                    uri.getFragment()
            ).toString();
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("fullUrl must be a valid URL.");
        }
    }
}
