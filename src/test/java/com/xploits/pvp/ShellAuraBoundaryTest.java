package com.xploits.pvp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fix round 1 of 0.8.0's burst tail (review I1): surround++ skips every crystal crystal-aura++ looks after itself, so it
 * must be told only the crystals of a placement of ours (in time or late), never the ones crystal-aura++ takes as ours
 * through a burst's tail, which may as well be an opponent's crystal on a base we just used. The two views are separate
 * accessors; which one surround++ calls lives in its adapter, which no core test runs, so this reads the source, as
 * {@code BoundaryTest} does for the console.
 */
class ShellAuraBoundaryTest {
    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final String WITH_TAIL = "ownCrystalsWithBurstTail(";
    private static final String WITHOUT_TAIL = "ownCrystalsWithoutBurstTail(";

    private static String read(String relative) throws IOException {
        return Files.readString(SOURCES.resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    void surroundPlusPlusIsToldOnlyTheCrystalsTheAuraLooksAfter() throws IOException {
        String shell = read("com/xploits/pvp/shell/SurroundPlusPlus.java");
        assertTrue(shell.contains(WITHOUT_TAIL), "surround++ reads the view without the burst tail");
        assertTrue(!shell.contains(WITH_TAIL), "surround++ must not read the view with the burst tail");
    }

    @Test
    void onlyCrystalAuraPlusPlusItselfTouchesTheViewWithTheTail() throws IOException {
        // The bench reads it (src/gametest); in the shipped code only its own declaration and delegation name it.
        List<String> users;
        try (Stream<Path> s = Files.walk(SOURCES)) {
            users = s.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    return Files.readString(f, StandardCharsets.UTF_8).contains(WITH_TAIL);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(f -> SOURCES.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
        assertEquals(List.of("com/xploits/pvp/crystal/CrystalAuraPlusPlus.java",
            "com/xploits/pvp/crystal/core/CrystalBrain.java"), users);
    }
}
