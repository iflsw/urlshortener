package com.urlshortener.exception;

/**
 * Thrown when no free alias could be generated within the allowed attempts.
 * This is a server-side condition, not a client error.
 */
public class AliasGenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AliasGenerationException(int attempts) {
        super("Could not generate a unique alias after " + attempts + " attempts.");
    }
}
