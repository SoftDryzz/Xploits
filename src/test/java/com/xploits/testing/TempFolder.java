package com.xploits.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A per-test temporary directory that survives brief Windows file locks (antivirus, search
 * indexer) instead of failing the test during cleanup.
 *
 * <p>JUnit's built-in {@code @TempDir} deletes the directory exactly once after the test and
 * throws if anything could not be removed, which is flaky on Windows when another process
 * still has a just-written file open for a few milliseconds. This helper retries the recursive
 * delete a few times with a short backoff, and if it still cannot finish, it reports the
 * leftover to stderr instead of failing the test: a stray temp folder must never turn an
 * otherwise-passing test red.
 */
public final class TempFolder {
    private static final int MAX_DELETE_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MILLIS = 75;

    private TempFolder() {
    }

    /** Creates a fresh temporary directory, the same way {@code @TempDir} does. */
    public static Path create() throws IOException {
        return Files.createTempDirectory("xploits-test-");
    }

    /**
     * Recursively deletes {@code root}, retrying with a short backoff when a file is still
     * held by another process. Never throws: a leftover that survives every retry is logged
     * to stderr instead, so it cannot fail a test that otherwise passed.
     */
    public static void delete(Path root) {
        if (root == null) {
            return;
        }
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_DELETE_ATTEMPTS; attempt++) {
            try {
                deleteRecursively(root);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < MAX_DELETE_ATTEMPTS) {
                    sleep();
                }
            }
        }
        System.err.println("TempFolder: leaving " + root + " behind after " + MAX_DELETE_ATTEMPTS
            + " failed delete attempts (likely a transient file lock): " + lastFailure);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                Files.delete(path);
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
