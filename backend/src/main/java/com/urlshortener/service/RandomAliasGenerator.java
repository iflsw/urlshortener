package com.urlshortener.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Generates unguessable aliases: 7 characters drawn uniformly from base62.
 * <p>
 * 62^7 is about 3.5 trillion combinations. SecureRandom keeps aliases
 * non-sequential and unpredictable, so stored URLs cannot be enumerated.
 */
@Component
public class RandomAliasGenerator implements AliasGenerator {

    static final String ALIAS_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    static final int GENERATED_ALIAS_LENGTH = 7;

    private final RandomGenerator random;

    /** Used by Spring. SecureRandom is thread-safe, so one shared bean is fine. */
    public RandomAliasGenerator() {
        this(new SecureRandom());
    }

    /** Lets tests supply a deterministic random source. */
    RandomAliasGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public String generate() {
        var alias = new StringBuilder(GENERATED_ALIAS_LENGTH);
        for (int i = 0; i < GENERATED_ALIAS_LENGTH; i++) {
            // nextInt(bound) is uniform; "nextInt() % 62" would bias some characters.
            alias.append(ALIAS_CHARS.charAt(random.nextInt(ALIAS_CHARS.length())));
        }
        return alias.toString();
    }
}
