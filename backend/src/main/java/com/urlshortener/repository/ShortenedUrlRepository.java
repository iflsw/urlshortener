package com.urlshortener.repository;

import com.urlshortener.model.UrlListItem;
import com.urlshortener.model.UrlPage;
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
        // Supports the newest-first ordering and the cursor condition in findPage.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_shortened_urls_created_at_id "
                + "ON shortened_urls (created_at, id)");
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
     * One page of URLs, newest first, starting after the given cursor (or from the newest when null).
     * <p>
     * Keyset ("cursor") paging: the condition selects rows strictly older than the cursor's
     * (created_at, id), so rows created or deleted between requests never cause duplicates or gaps.
     * Ties on created_at are broken by id (AUTOINCREMENT, so insertion order).
     * Fetches one extra row to detect whether another page exists, without a COUNT query.
     */
    public UrlPage findPage(String baseUrl, PageCursor after, int size) {
        List<PageRow> rows = after == null
                ? jdbc.query("SELECT id, alias, full_url, created_at FROM shortened_urls "
                                + "ORDER BY created_at DESC, id DESC LIMIT ?",
                        PAGE_ROW_MAPPER, size + 1)
                // Equivalent to (created_at, id) < (?, ?); written out so it is portable across databases.
                : jdbc.query("SELECT id, alias, full_url, created_at FROM shortened_urls "
                                + "WHERE created_at < ? OR (created_at = ? AND id < ?) "
                                + "ORDER BY created_at DESC, id DESC LIMIT ?",
                        PAGE_ROW_MAPPER, after.createdAt(), after.createdAt(), after.id(), size + 1);

        var hasNextPage = rows.size() > size;
        var pageRows = hasNextPage ? rows.subList(0, size) : rows;
        var items = pageRows.stream()
                .map(row -> new UrlListItem(row.alias(), row.fullUrl(), baseUrl + "/" + row.alias()))
                .toList();
        String nextCursor = null;
        if (hasNextPage) {
            var last = pageRows.get(pageRows.size() - 1);
            nextCursor = new PageCursor(last.createdAt(), last.id()).encode();
        }
        return new UrlPage(items, nextCursor);
    }

    public boolean deleteByAlias(String alias) {
        var rows = jdbc.update("DELETE FROM shortened_urls WHERE alias = ?", alias);
        return rows > 0;
    }

    private record PageRow(long id, String alias, String fullUrl, String createdAt) {
    }

    private static final RowMapper<PageRow> PAGE_ROW_MAPPER = (ResultSet rs, int rowNum) -> new PageRow(
            rs.getLong("id"),
            rs.getString("alias"),
            rs.getString("full_url"),
            rs.getString("created_at"));
}
