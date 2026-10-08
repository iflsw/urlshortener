package com.urlshortener.repository;

import com.urlshortener.model.UrlListItem;
import com.urlshortener.model.UrlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void findPage_ReturnsNewestFirst() {
        save("old", "2026-10-01T09:00:00Z");
        save("newest", "2026-10-03T09:00:00Z");
        save("middle", "2026-10-02T09:00:00Z");

        assertEquals(List.of("newest", "middle", "old"), aliases(repository.findPage(BASE_URL, null, 100).items()));
    }

    @Test
    void findPage_SameSecondDifferentMillis_SortsByActualTime() {
        // As plain Instant.toString() text, "...:00Z" sorts after "...:00.500Z", which is wrong.
        save("earlier", "2026-10-07T12:00:00Z");
        save("later", "2026-10-07T12:00:00.500Z");

        assertEquals(List.of("later", "earlier"), aliases(repository.findPage(BASE_URL, null, 100).items()));
    }

    @Test
    void findPage_IdenticalTimestamps_MostRecentlyInsertedFirst() {
        save("first", "2026-10-07T12:00:00Z");
        save("second", "2026-10-07T12:00:00Z");

        assertEquals(List.of("second", "first"), aliases(repository.findPage(BASE_URL, null, 100).items()));
    }

    // ---- cursor paging ----

    @Test
    void findPage_FirstPage_ReturnsNewestItemsAndNextCursor() {
        saveFive();

        var page = repository.findPage(BASE_URL, null, 2);

        assertEquals(List.of("e", "d"), aliases(page.items()));
        assertNotNull(page.nextCursor());
    }

    @Test
    void findPage_ItemsHaveShortUrls() {
        save("abc", "2026-10-01T09:00:00Z");

        var item = repository.findPage(BASE_URL, null, 20).items().get(0);

        assertEquals("http://localhost/abc", item.getShortUrl());
        assertEquals("https://example.com/abc", item.getFullUrl());
    }

    @Test
    void findPage_WalkingAllPages_ReturnsEveryRowOnceNewestFirst() {
        saveFive();

        assertEquals(List.of("e", "d", "c", "b", "a"), walkAll(2));
    }

    @Test
    void findPage_RowsFillExactlyOnePage_HasNoNextCursor() {
        save("a", "2026-10-01T09:00:00Z");
        save("b", "2026-10-02T09:00:00Z");

        var page = repository.findPage(BASE_URL, null, 2);

        assertEquals(List.of("b", "a"), aliases(page.items()));
        assertNull(page.nextCursor());
    }

    @Test
    void findPage_EmptyTable_ReturnsEmptyPageWithoutCursor() {
        var page = repository.findPage(BASE_URL, null, 20);

        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void findPage_NewerRowCreatedMidWalk_CausesNoDuplicatesOrGaps() {
        save("a", "2026-10-01T09:00:00Z");
        save("b", "2026-10-02T09:00:00Z");
        save("c", "2026-10-03T09:00:00Z");
        var first = repository.findPage(BASE_URL, null, 2);

        save("d", "2026-10-04T09:00:00Z");   // with offset paging, this would shift "b" onto page 2
        var second = repository.findPage(BASE_URL, PageCursor.decode(first.nextCursor()), 2);

        assertEquals(List.of("c", "b"), aliases(first.items()));
        assertEquals(List.of("a"), aliases(second.items()));
    }

    @Test
    void findPage_CursorRowDeletedMidWalk_ContinuesAfterIt() {
        save("a", "2026-10-01T09:00:00Z");
        save("b", "2026-10-02T09:00:00Z");
        save("c", "2026-10-03T09:00:00Z");
        var first = repository.findPage(BASE_URL, null, 2);

        repository.deleteByAlias("b");       // the row the cursor points at
        var second = repository.findPage(BASE_URL, PageCursor.decode(first.nextCursor()), 2);

        assertEquals(List.of("a"), aliases(second.items()));
    }

    @Test
    void findPage_IdenticalTimestampsAcrossPageBoundary_UsesIdAsTieBreaker() {
        save("x", "2026-10-07T12:00:00Z");
        save("y", "2026-10-07T12:00:00Z");
        save("z", "2026-10-07T12:00:00Z");

        assertEquals(List.of("z", "y", "x"), walkAll(2));
    }

    private void saveFive() {
        save("a", "2026-10-01T09:00:00Z");
        save("b", "2026-10-02T09:00:00Z");
        save("c", "2026-10-03T09:00:00Z");
        save("d", "2026-10-04T09:00:00Z");
        save("e", "2026-10-05T09:00:00Z");
    }

    /** Follows nextCursor until the last page, failing if it loops. */
    private List<String> walkAll(int size) {
        var all = new ArrayList<String>();
        PageCursor cursor = null;
        for (int pages = 0; pages < 100; pages++) {
            UrlPage page = repository.findPage(BASE_URL, cursor, size);
            all.addAll(aliases(page.items()));
            if (page.nextCursor() == null) {
                return all;
            }
            cursor = PageCursor.decode(page.nextCursor());
        }
        throw new AssertionError("Paging did not terminate");
    }

    private static final String BASE_URL = "http://localhost";

    private void save(String alias, String instant) {
        repository.saveIfAliasAvailable(alias, "https://example.com/" + alias, Instant.parse(instant));
    }

    private static List<String> aliases(List<UrlListItem> items) {
        return items.stream().map(UrlListItem::getAlias).toList();
    }
}
