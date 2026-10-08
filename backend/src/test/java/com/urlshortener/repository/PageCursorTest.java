package com.urlshortener.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageCursorTest {

    @Test
    void encodeThenDecode_RoundTrips() {
        var cursor = new PageCursor("2026-10-07T12:00:00.123Z", 42);

        assertEquals(cursor, PageCursor.decode(cursor.encode()));
    }

    @Test
    void encode_IsUrlSafe() {
        // Safe to put in a query string without escaping: base64url, no padding.
        var encoded = new PageCursor("2026-10-07T12:00:00.123Z", 42).encode();

        assertTrue(encoded.matches("[A-Za-z0-9_-]+"), encoded);
    }

    @Test
    void decode_AcceptsLegacyTimestampFormat() {
        // Rows written before the fixed-width format still produce valid cursors.
        var cursor = new PageCursor("2026-10-07T12:00:00Z", 7);

        assertEquals(cursor, PageCursor.decode(cursor.encode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                                   // empty
            "no-separator",                       // missing '|'
            "|5",                                 // missing timestamp
            "2026-10-07T12:00:00.000Z|",          // missing id
            "2026-10-07T12:00:00.000Z|abc",       // id not a number
            "not-a-date|5"                        // timestamp not an instant
    })
    void decode_MalformedContent_Throws(String rawCursor) {
        var encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));

        var ex = assertThrows(IllegalArgumentException.class, () -> PageCursor.decode(encoded));
        assertEquals("Invalid cursor.", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!", "abc$def", "a b"})
    void decode_NotBase64Url_Throws(String cursor) {
        var ex = assertThrows(IllegalArgumentException.class, () -> PageCursor.decode(cursor));
        assertEquals("Invalid cursor.", ex.getMessage());
    }
}
