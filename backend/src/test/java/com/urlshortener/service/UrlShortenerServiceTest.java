package com.urlshortener.service;

import com.urlshortener.model.ShortenUrlRequest;
import com.urlshortener.model.ShortenUrlResponse;
import com.urlshortener.repository.ShortenedUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        // Simulates losing the race: the alias is free when checked,
        // but another request inserts it first, so the atomic insert reports a conflict.
        // (Mockito's default for existsByAlias is false, i.e. "looked free".)
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

    private static ShortenUrlRequest request(String fullUrl, String customAlias) {
        var request = new ShortenUrlRequest();
        request.setFullUrl(fullUrl);
        request.setCustomAlias(customAlias);
        return request;
    }

}
