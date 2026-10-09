package com.urlshortener.service;

/**
 * Produces candidate aliases for shortened URLs.
 * <p>
 * Implementations must return a value that satisfies the alias rules
 * (ASCII letters and digits, 2-64 characters). Uniqueness is NOT guaranteed
 * here: the caller enforces it against the database and retries on collision.
 */
@FunctionalInterface
public interface AliasGenerator {

    String generate();
}
