package com.urlshortener.repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Position in the newest-first URL list: the sort key (created_at, id) of the last item on a page.
 * <p>
 * Encoded as base64url so clients treat it as an opaque token and can pass it in a query string
 * unescaped. It is not a security boundary: anyone can decode it, and that is harmless.
 */
public record PageCursor(String createdAt, long id) {

    private static final char SEPARATOR = '|';

    public String encode() {
        var raw = createdAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws IllegalArgumentException with message "Invalid cursor." for anything not produced by {@link #encode()}
     */
    public static PageCursor decode(String cursor) {
        if (cursor == null) {
            throw invalid();
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var separator = raw.lastIndexOf(SEPARATOR);
            if (separator <= 0) {
                throw invalid();
            }
            var createdAt = raw.substring(0, separator);
            Instant.parse(createdAt);   // validation only: the stored text is what gets compared
            var id = Long.parseLong(raw.substring(separator + 1));
            return new PageCursor(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // Base64 errors and NumberFormatException are IllegalArgumentExceptions
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid cursor.");
    }
}
