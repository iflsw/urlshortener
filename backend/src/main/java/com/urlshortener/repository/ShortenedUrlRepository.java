package com.urlshortener.repository;

import com.urlshortener.model.UrlListItem;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class ShortenedUrlRepository {

    /**
     * Fixed-width UTC timestamps (always millisecond precision), so created_at sorts correctly as text.
     * Instant.toString() drops zero fractions ("...:00Z" vs "...:00.123Z"), which breaks text ordering.
     */
    static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

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
                alias, fullUrl, CREATED_AT_FORMAT.format(createdAt));
        return rows == 1;
    }

    public Optional<String> findFullUrlByAlias(String alias) {
        var results = jdbc.query("SELECT full_url FROM shortened_urls WHERE alias = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("full_url"),
                alias);
        return results.stream().findFirst();
    }

    /**
     * All mappings, newest first. id breaks ties between identical timestamps
     * (it is AUTOINCREMENT, so it follows insertion order).
     */
    public List<UrlListItem> findAll(String baseUrl) {
        return jdbc.query("SELECT alias, full_url FROM shortened_urls ORDER BY created_at DESC, id DESC",
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
