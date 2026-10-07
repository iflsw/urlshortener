package com.urlshortener.repository;

import com.urlshortener.model.UrlListItem;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ShortenedUrlRepository {

    private final JdbcTemplate jdbc;

    public ShortenedUrlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS shortened_urls ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "alias TEXT NOT NULL UNIQUE, "
                + "full_url TEXT NOT NULL, "
                + "created_at TEXT NOT NULL"
                + ")");
    }

    public boolean existsByAlias(String alias) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM shortened_urls WHERE alias = ?",
                Integer.class,
                alias);
        return count != null && count > 0;
    }

    public void save(String alias, String fullUrl, Instant createdAt) {
        jdbc.update("INSERT INTO shortened_urls (alias, full_url, created_at) VALUES (?, ?, ?)",
                alias, fullUrl, createdAt.toString());
    }

    /**
     * Inserts the mapping only if the alias is not already taken.
     * <p>
     * Atomic: the UNIQUE constraint decides, so two concurrent requests can never
     * both claim the same alias. Only an alias conflict is ignored; any other
     * constraint violation still throws.
     *
     * @return true if the row was inserted, false if the alias already exists
     */
    public boolean saveIfAliasAvailable(String alias, String fullUrl, Instant createdAt) {
        var rows = jdbc.update("INSERT INTO shortened_urls (alias, full_url, created_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(alias) DO NOTHING",
                alias, fullUrl, createdAt.toString());
        return rows == 1;
    }

    public Optional<String> findFullUrlByAlias(String alias) {
        var results = jdbc.query("SELECT full_url FROM shortened_urls WHERE alias = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("full_url"),
                alias);
        return results.stream().findFirst();
    }

    public List<UrlListItem> findAll(String baseUrl) {
        return jdbc.query("SELECT alias, full_url FROM shortened_urls",
                urlListItemMapper(baseUrl));
    }

    public boolean deleteByAlias(String alias) {
        var rows = jdbc.update("DELETE FROM shortened_urls WHERE alias = ?", alias);
        return rows > 0;
    }

    private RowMapper<UrlListItem> urlListItemMapper(String baseUrl) {
        return (ResultSet rs, int rowNum) -> new UrlListItem(
                rs.getString("alias"),
                rs.getString("full_url"),
                baseUrl + "/" + rs.getString("alias")
        );
    }
}
