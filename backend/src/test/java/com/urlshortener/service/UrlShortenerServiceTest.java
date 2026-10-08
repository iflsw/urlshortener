package com.urlshortener.service;

import com.urlshortener.model.ShortenUrlRequest;
import com.urlshortener.model.ShortenUrlResponse;
import com.urlshortener.repository.ShortenedUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

public class UrlShortenerServiceTest {

    private static final String BASE_URL = "http://localhost:8080";
    private UrlShortenerService service;
    private AliasGenerator generator = new RandomAliasGenerator();

    @BeforeEach
    void setUp() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbcTemplate = new JdbcTemplate(dataSource);
        var repository = new ShortenedUrlRepository(jdbcTemplate);
        repository.init();
        service = new UrlShortenerService(repository, generator);

    }

    @Test
    void shorten_WithValidUrl_ReturnsShortUrlResponse() {
        var request = new ShortenUrlRequest();
        request.setFullUrl("https://example.com/test");

        ShortenUrlResponse response = service.shorten(request, BASE_URL);

        assertNotNull(response.getAlias());
        assertEquals("https://example.com/test", response.getFullUrl());
        assertTrue(response.getShortUrl().startsWith(BASE_URL + "/"));
    }

    @Test
    void shorten_WithCustomAlias_ReturnsCustomAlias() {
        var request = new ShortenUrlRequest();
        request.setFullUrl("https://example.com");
        request.setCustomAlias("my-alias");

        ShortenUrlResponse response = service.shorten(request, BASE_URL);

        assertEquals("my-alias", response.getAlias());
        assertEquals(BASE_URL + "/my-alias", response.getShortUrl());
    }

    @Test
    void getFullUrl_ReturnsExistingUrl() throws ExecutionException, InterruptedException {
        var request = new ShortenUrlRequest();
        request.setFullUrl("https://example.com");
        request.setCustomAlias("example");
        service.shorten(request, BASE_URL);
        String fullUrl = CompletableFuture.supplyAsync(() -> service.getFullUrl("example")).get();

        //add trailing space to matchnormalised format
        assertEquals("https://example.com/", fullUrl);
    }


    @Test
    void getFullUrl_ReturnsExistingUrlWithUri() throws ExecutionException, InterruptedException {
        var request = new ShortenUrlRequest();
        request.setFullUrl("https://example.com/something");
        request.setCustomAlias("example");
        service.shorten(request, BASE_URL);
        String fullUrl = CompletableFuture.supplyAsync(() -> service.getFullUrl("example")).get();

        //add trailing space to matchnormalised format
        assertEquals("https://example.com/something", fullUrl);
    }

    @Test
    void shorten_CustomAliasClaimedConcurrently_ThrowsAliasTaken() {
        // Simulates losing the race: another request inserts the alias first,
        // so the atomic insert reports a conflict.
        var repository = mock(ShortenedUrlRepository.class);
        given(repository.saveIfAliasAvailable(eq("promo"), anyString(), any())).willReturn(false);
        var racingService = new UrlShortenerService(repository, generator);

        var ex = assertThrows(IllegalStateException.class,
                () -> racingService.shorten(request("https://second.com", "promo"), BASE_URL));

        assertEquals("The alias 'promo' is already taken.", ex.getMessage());
    }

    @Test
    void shorten_CustomAliasAlreadyTaken_ThrowsAndKeepsOriginalMapping() {
        service.shorten(request("https://first.com", "promo"), BASE_URL);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.shorten(request("https://second.com", "promo"), BASE_URL));

        assertEquals("The alias 'promo' is already taken.", ex.getMessage());
        assertEquals("https://first.com/", service.getFullUrl("promo"));
    }

    // ---- delete ----

    @Test
    void delete_ExistingAlias_ReturnsTrueAndRemovesMapping() {
        service.shorten(request("https://example.com", "gone"), BASE_URL);

        assertTrue(service.delete("gone"));
        assertNull(service.getFullUrl("gone"));
    }

    @Test
    void delete_ExistingAlias_LeavesNoRowsBehind() {
        // Guards against the old bug, which inserted and deleted an "<alias>-deleted" row
        // and never touched the original.
        service.shorten(request("https://example.com", "gone"), BASE_URL);
        service.delete("gone");

        assertTrue(service.getPage(BASE_URL, null, 100).items().isEmpty());
    }

    @Test
    void delete_UnknownAlias_ReturnsFalse() {
        assertFalse(service.delete("missing"));
    }

    @Test
    void delete_Twice_SecondCallReturnsFalse() {
        service.shorten(request("https://example.com", "gone"), BASE_URL);

        assertTrue(service.delete("gone"));
        assertFalse(service.delete("gone"));
    }

    @Test
    void delete_OnlyRemovesTheGivenAlias() {
        service.shorten(request("https://example.com", "gone"), BASE_URL);
        service.shorten(request("https://other.com", "kept"), BASE_URL);

        service.delete("gone");

        assertEquals("https://other.com/", service.getFullUrl("kept"));
    }

    // ---- custom alias validation ----

    // Annotation values must be compile-time constants, so these are literals rather than "a".repeat(n).
    private static final String ALIAS_64 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String ALIAS_65 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String INVALID_ALIAS_MESSAGE =
            "Alias may only contain letters, numbers, and hyphens (2–64 characters).";

    @ParameterizedTest
    @ValueSource(strings = {"ab", "my-alias", "ABC123", "a-b-c", "0-9", ALIAS_64})
    void shorten_ValidCustomAlias_IsAccepted(String alias) {
        var response = service.shorten(request("https://example.com", alias), BASE_URL);

        assertEquals(alias, response.getAlias());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a",            // too short
            ALIAS_65,       // too long
            "café",         // non-ASCII letter
            "١٢٣",          // Arabic-Indic digits
            "ａｂ",          // full-width letters
            "my_alias",     // underscore
            "my alias",     // inner space
            "my.alias",     // dot (would also bypass the nginx alias route)
            "a/b",          // path separator
            "a\nb"         // control character (surrounding whitespace is trimmed, so test an inner one)
    })
    void shorten_InvalidCustomAlias_ThrowsAndSavesNothing(String alias) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.shorten(request("https://example.com", alias), BASE_URL));

        assertEquals(INVALID_ALIAS_MESSAGE, ex.getMessage());
        assertTrue(service.getPage(BASE_URL, null, 100).items().isEmpty());
    }

    @Test
    void shorten_CustomAliasWithSurroundingSpaces_IsTrimmed() {
        var response = service.shorten(request("https://example.com", "  my-alias  "), BASE_URL);

        assertEquals("my-alias", response.getAlias());
    }

    @ParameterizedTest
    @ValueSource(strings = {"urls", "shorten"})
    void shorten_ReservedCustomAlias_ThrowsAndSavesNothing(String alias) {
        // These paths belong to API endpoints, so such an alias could never redirect.
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.shorten(request("https://example.com", alias), BASE_URL));

        assertEquals("The alias '" + alias + "' is reserved.", ex.getMessage());
        assertTrue(service.getPage(BASE_URL, null, 100).items().isEmpty());
    }

    // ---- paging ----

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void getPage_SizeOutOfRange_Throws(int size) {
        var ex = assertThrows(IllegalArgumentException.class, () -> service.getPage(BASE_URL, null, size));

        assertEquals("size must be between 1 and 100.", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void getPage_SizeAtBounds_IsAccepted(int size) {
        service.shorten(request("https://example.com", "abc"), BASE_URL);

        assertEquals(1, service.getPage(BASE_URL, null, size).items().size());
    }

    @Test
    void getPage_MalformedCursor_Throws() {
        var ex = assertThrows(IllegalArgumentException.class, () -> service.getPage(BASE_URL, "!!!", 20));

        assertEquals("Invalid cursor.", ex.getMessage());
    }

    @Test
    void getPage_BlankCursor_ReturnsFirstPage() {
        service.shorten(request("https://example.com", "abc"), BASE_URL);

        assertEquals("abc", service.getPage(BASE_URL, "  ", 20).items().get(0).getAlias());
    }

    @Test
    void getPage_FollowingNextCursor_ReturnsTheRest() {
        service.shorten(request("https://one.com", "one"), BASE_URL);
        service.shorten(request("https://two.com", "two"), BASE_URL);

        var first = service.getPage(BASE_URL, null, 1);
        var second = service.getPage(BASE_URL, first.nextCursor(), 1);

        assertEquals("two", first.items().get(0).getAlias());
        assertEquals("one", second.items().get(0).getAlias());
        assertNull(second.nextCursor());
    }

    private static ShortenUrlRequest request(String fullUrl, String customAlias) {
        var request = new ShortenUrlRequest();
        request.setFullUrl(fullUrl);
        request.setCustomAlias(customAlias);
        return request;
    }

}
