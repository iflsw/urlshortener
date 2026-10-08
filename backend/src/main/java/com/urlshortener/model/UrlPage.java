package com.urlshortener.model;

import java.util.List;

/**
 * One page of URLs, newest first.
 *
 * @param items      the URLs on this page
 * @param nextCursor opaque cursor for the next page, or null if this is the last page
 */
public record UrlPage(List<UrlListItem> items, String nextCursor) {
}
