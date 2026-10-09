package com.urlshortener.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomAliasGeneratorTest {

    private static final Pattern SEVEN_BASE62_CHARS = Pattern.compile("^[a-zA-Z0-9]{7}$");

    @Test
    void generate_ReturnsSevenBase62Characters() {
        var generator = new RandomAliasGenerator();

        for (int i = 0; i < 1_000; i++) {
            var alias = generator.generate();
            assertTrue(SEVEN_BASE62_CHARS.matcher(alias).matches(), "unexpected alias: " + alias);
        }
    }

    @Test
    void generate_MapsEachRandomIndexToTheAlphabet() {
        // Boundaries of each character range: a, b, z, A, Z, 0, 9
        var generator = new RandomAliasGenerator(fixedSequence(0, 1, 25, 26, 51, 52, 61));

        assertEquals("abzAZ09", generator.generate());
    }

    @Test
    void generate_RequestsUniformIndexWithinAlphabetSize() {
        var requestedBounds = new ArrayList<Integer>();
        RandomGenerator recordingRandom = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException("generator must use nextInt(bound)");
            }

            @Override
            public int nextInt(int bound) {
                requestedBounds.add(bound);
                return 0;
            }
        };

        new RandomAliasGenerator(recordingRandom).generate();

        assertEquals(Collections.nCopies(7, 62), requestedBounds);
    }

    @Test
    void generate_ProducesDistinctAliases() {
        // Chance of a legitimate duplicate in 1,000 draws from 62^7 is about 1 in 7 million.
        var generator = new RandomAliasGenerator();
        var aliases = new HashSet<String>();

        for (int i = 0; i < 1_000; i++) {
            aliases.add(generator.generate());
        }

        assertEquals(1_000, aliases.size());
    }

    @Test
    void constructor_RejectsNullRandom() {
        assertThrows(NullPointerException.class, () -> new RandomAliasGenerator(null));
    }

    private static RandomGenerator fixedSequence(int... values) {
        var queue = new ArrayDeque<Integer>();
        for (int value : values) {
            queue.add(value);
        }
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public int nextInt(int bound) {
                return queue.removeFirst();
            }
        };
    }
}
