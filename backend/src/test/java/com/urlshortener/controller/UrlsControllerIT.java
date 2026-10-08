package com.urlshortener.controller;

import com.urlshortener.model.ShortenUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.FOUND;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        // Each SQLite in-memory connection is a separate, empty database.
        // A single pooled connection guarantees the test and the server share one DB.
        "spring.datasource.hikari.maximum-pool-size=1"
})
class UrlsControllerIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM shortened_urls");
        jdbcTemplate.update(
                "INSERT INTO shortened_urls (alias, full_url, created_at) VALUES (?, ?, ?)",
                "redir",
                "https://target.com/",
                // Fixed and older than anything the tests create, so list ordering is deterministic.
                "2020-01-01T00:00:00.000Z"
        );
    }

    @Test
    void getAlias_ExistingAlias_Returns302WithLocation() {
        var response = restTemplate.getForEntity("/redir", String.class);
        assertThat(response.getStatusCode()).isEqualTo(FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://target.com/");
    }

    @Test
    void shorten_GeneratedAlias_Returns201AndRedirects() {
        var response = restTemplate.postForEntity("/shorten",
                Map.of("fullUrl", "https://example.com/page"), ShortenUrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(CREATED);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getAlias()).matches("^[a-zA-Z0-9]{7}$");
        assertThat(body.getFullUrl()).isEqualTo("https://example.com/page");
        assertThat(body.getShortUrl()).endsWith("/" + body.getAlias());
        assertThat(response.getHeaders().getLocation()).hasToString(body.getShortUrl());

        var redirect = restTemplate.getForEntity("/" + body.getAlias(), String.class);
        assertThat(redirect.getStatusCode()).isEqualTo(FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/page");
    }

    @Test
    void shorten_CustomAlias_Returns201WithThatAlias() {
        var response = restTemplate.postForEntity("/shorten",
                Map.of("fullUrl", "https://example.com", "customAlias", "my-alias"), ShortenUrlResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAlias()).isEqualTo("my-alias");
        assertThat(response.getBody().getShortUrl()).endsWith("/my-alias");
    }

    @Test
    void shorten_InvalidUrl_Returns400WithError() {
        var response = restTemplate.postForEntity("/shorten",
                Map.of("fullUrl", "not a url"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void shorten_TakenCustomAlias_Returns400AndKeepsOriginal() {
        var response = restTemplate.postForEntity("/shorten",
                Map.of("fullUrl", "https://other.com", "customAlias", "redir"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");

        var redirect = restTemplate.getForEntity("/redir", String.class);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://target.com/");
    }

    @Test
    void delete_ExistingAlias_Returns204AndAliasStopsRedirecting() {
        assertThat(delete("redir").getStatusCode()).isEqualTo(NO_CONTENT);

        assertThat(restTemplate.getForEntity("/redir", String.class).getStatusCode()).isEqualTo(NOT_FOUND);
    }

    @Test
    void delete_ExistingAlias_RemovesItFromTheList() {
        delete("redir");

        assertThat(aliases(getPage(null, 20))).isEmpty();
    }

    @Test
    void delete_UnknownAlias_Returns404() {
        assertThat(delete("missing").getStatusCode()).isEqualTo(NOT_FOUND);
    }

    @Test
    void delete_Twice_SecondReturns404() {
        assertThat(delete("redir").getStatusCode()).isEqualTo(NO_CONTENT);
        assertThat(delete("redir").getStatusCode()).isEqualTo(NOT_FOUND);
    }

    private ResponseEntity<Void> delete(String alias) {
        return restTemplate.exchange("/" + alias, HttpMethod.DELETE, null, Void.class);
    }

    @Test
    void shorten_NonAsciiCustomAlias_Returns400WithError() {
        var response = restTemplate.postForEntity("/shorten",
                Map.of("fullUrl", "https://example.com", "customAlias", "café"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    // ---- GET /urls (cursor paging) ----

    @Test
    void list_WalkingAllPages_ReturnsEveryUrlOnceNewestFirst() {
        create("p1");
        create("p2");
        create("p3");
        create("p4");

        assertThat(walkAll(2)).containsExactly("p4", "p3", "p2", "p1", "redir");
    }

    @Test
    void list_UrlCreatedMidWalk_CausesNoDuplicatesOrGaps() {
        create("p1");
        create("p2");
        create("p3");
        var first = getPage(null, 2);

        create("p4");   // newer than everything: must not appear on later pages or shift them
        var second = getPage((String) first.get("nextCursor"), 2);
        var third = getPage((String) second.get("nextCursor"), 2);

        assertThat(aliases(first)).containsExactly("p3", "p2");
        assertThat(aliases(second)).containsExactly("p1", "redir");
        assertThat(aliases(third)).isEmpty();
        assertThat(third.get("nextCursor")).isNull();
    }

    @Test
    void list_SizeOutOfRange_Returns400WithError() {
        var response = restTemplate.getForEntity("/urls?size=0", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    void list_MalformedCursor_Returns400WithError() {
        var response = restTemplate.getForEntity("/urls?cursor=not-a-cursor", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    private void create(String alias) {
        var response = restTemplate.postForEntity("/shorten",
                Map.of("fullUrl", "https://example.com/" + alias, "customAlias", alias), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(CREATED);
    }

    private Map<String, Object> getPage(String cursor, int size) {
        var url = cursor == null ? "/urls?size=" + size : "/urls?size=" + size + "&cursor=" + cursor;
        var response = restTemplate.exchange(url, HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<String> aliases(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .map(item -> (String) item.get("alias"))
                .toList();
    }

    private List<String> walkAll(int size) {
        var all = new ArrayList<String>();
        String cursor = null;
        for (int pages = 0; pages < 100; pages++) {
            var page = getPage(cursor, size);
            all.addAll(aliases(page));
            cursor = (String) page.get("nextCursor");
            if (cursor == null) {
                return all;
            }
        }
        throw new AssertionError("Paging did not terminate");
    }
}
