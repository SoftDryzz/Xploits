package com.xploits.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The report hygiene scan (spec {@code 2026-09-25-ingame-bench}, §Proving the bench works): after the
 * last write, every {@code .json} and {@code .md} file under {@code build/bench} is searched for three
 * numbers in a row separated by spaces or commas, which is what a position looks like. A hit is ERROR.
 * Hits are named by file and line number only: the line itself is never printed.
 */
final class Hygiene {
    static final Pattern POSITION = Pattern.compile("-?\\d+(\\.\\d+)?[ ,]+-?\\d+(\\.\\d+)?[ ,]+-?\\d+(\\.\\d+)?");

    private Hygiene() {
    }

    /** The lines that match, as {@code <file> line <n>} with the file relative to {@code folder}. */
    static List<String> scan(Path folder) throws IOException {
        List<String> hits = new ArrayList<>();
        if (!Files.isDirectory(folder)) return hits;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.filter(Files::isRegularFile).filter(Hygiene::scanned).sorted().toList();
        }
        for (Path file : files) hits.addAll(scanFile(file, folder.relativize(file).toString().replace('\\', '/')));
        return hits;
    }

    /** One file's matching lines, named {@code <name> line <n>}. */
    static List<String> scanFile(Path file, String name) throws IOException {
        List<String> hits = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (POSITION.matcher(lines.get(i)).find()) hits.add(name + " line " + (i + 1));
        }
        return hits;
    }

    private static boolean scanned(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") || name.endsWith(".md");
    }
}
