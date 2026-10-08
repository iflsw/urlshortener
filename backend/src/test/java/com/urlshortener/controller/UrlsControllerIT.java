package com.urlshortener.controller;

import com.urlshortener.model.ShortenUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
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
                Instant.now().toString()
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

        var list = restTemplate.getForEntity("/urls", List.class);
        assertThat(list.getBody()).isEmpty();
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
}
