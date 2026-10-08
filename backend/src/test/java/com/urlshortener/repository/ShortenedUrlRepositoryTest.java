package com.urlshortener.repository;

import com.urlshortener.model.UrlListItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortenedUrlRepositoryTest {

    private JdbcTemplate jdbc;
    private ShortenedUrlRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        repository = new ShortenedUrlRepository(jdbc);
        repository.init();
    }

    @ParameterizedTest
    @CsvSource({
            // Instant.toString() drops zero fractions, so these would otherwise have different widths
            "2026-10-07T12:00:00Z,           2026-10-07T12:00:00.000Z",
            "2026-10-07T12:00:00.1Z,         2026-10-07T12:00:00.100Z",
            "2026-10-07T12:00:00.123Z,       2026-10-07T12:00:00.123Z",
            "2026-10-07T12:00:00.123456789Z, 2026-10-07T12:00:00.123Z"   // truncated to millis
    })
    void saveIfAliasAvailable_StoresCreatedAtInFixedWidthUtcFormat(String instant, String expectedStored) {
        repository.saveIfAliasAvailable("abc", "https://example.com/", Instant.parse(instant));

        var stored = jdbc.queryForObject(
                "SELECT created_at FROM shortened_urls WHERE alias = 'abc'", String.class);
        assertEquals(expectedStored, stored);
    }

    // ---- ordering ----

    @Test
    void findAll_ReturnsNewestFirst() {
        save("old", "2026-10-01T09:00:00Z");
        save("newest", "2026-10-03T09:00:00Z");
        save("middle", "2026-10-02T09:00:00Z");

        assertEquals(List.of("newest", "middle", "old"), aliases(repository.findAll(BASE_URL)));
    }

    @Test
    void findAll_SameSecondDifferentMillis_SortsByActualTime() {
        // As plain Instant.toString() text, "...:00Z" sorts after "...:00.500Z", which is wrong.
        save("earlier", "2026-10-07T12:00:00Z");
        save("later", "2026-10-07T12:00:00.500Z");

        assertEquals(List.of("later", "earlier"), aliases(repository.findAll(BASE_URL)));
    }

    @Test
    void findAll_IdenticalTimestamps_MostRecentlyInsertedFirst() {
        save("first", "2026-10-07T12:00:00Z");
        save("second", "2026-10-07T12:00:00Z");

        assertEquals(List.of("second", "first"), aliases(repository.findAll(BASE_URL)));
    }

    private static final String BASE_URL = "http://localhost";

    private void save(String alias, String instant) {
        repository.saveIfAliasAvailable(alias, "https://example.com/" + alias, Instant.parse(instant));
    }

    private static List<String> aliases(List<UrlListItem> items) {
        return items.stream().map(UrlListItem::getAlias).toList();
    }
}
