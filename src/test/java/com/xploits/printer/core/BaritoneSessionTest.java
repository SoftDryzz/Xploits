package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printer spec §7 (N-M9, M10): settings.txt, the session's values, the restoration, the saved file; Review Focus 4. */
class BaritoneSessionTest {
    private static Map<String, Boolean> values(boolean cc, boolean crc, boolean ab, boolean ap, boolean aw) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("censorCoordinates", cc);
        m.put("censorRanCommands", crc);
        m.put("allowBreak", ab);
        m.put("allowPlace", ap);
        m.put("allowWaterBucketFall", aw);
        return m;
    }

    @Test
    void readsTheFileAsBaritoneDoes() {
        Map<String, String> file = BaritoneSession.parse(List.of("# a comment", "// another", "", "AllowBreak   false",
            "allowplace false  ", "censorcoordinates true", "chatControl false", "notasetting"));
        assertEquals(Map.of("allowbreak", "false", "allowplace", "false  ", "censorcoordinates", "true",
            "chatcontrol", "false"), file);
        assertEquals(Optional.of(values(true, false, false, false, true)), BaritoneSession.playerValues(file));
    }

    @Test
    void noFileMeansBaritonesDefaults() {
        assertEquals(Optional.of(values(false, false, true, true, true)),
            BaritoneSession.playerValues(BaritoneSession.parse(List.of())));
    }

    @Test
    void anUnreadableValueIsNotGuessed() {
        assertEquals(Optional.empty(), BaritoneSession.playerValues(BaritoneSession.parse(List.of("allowBreak yes"))));
    }

    @Test
    void aTrueWithTrailingSpacesIsRefusedBecauseBaritoneReadsItAsFalse() {
        // Baritone parses the raw value with Boolean.parseBoolean: "true  " is false there (jar, baritone/em$b BOOLEAN).
        assertEquals(Optional.empty(), BaritoneSession.playerValues(BaritoneSession.parse(List.of("censorCoordinates true  "))));
        assertEquals(Optional.empty(), BaritoneSession.playerValues(BaritoneSession.parse(List.of("allowBreak true\t"))));
        assertEquals(Optional.of(values(false, false, true, true, true)),
            BaritoneSession.playerValues(BaritoneSession.parse(List.of("allowBreak TRUE"))));
    }

    @Test
    void theSessionSetsTheCensorPairFirst() {
        assertEquals(List.of("#set censorCoordinates true", "#set censorRanCommands true", "#set allowBreak false",
            "#set allowPlace false", "#set allowWaterBucketFall false"), BaritoneSession.preparation("#"));
    }

    @Test
    void mineRestoresThePlayersOwnValuesTheCensorPairLast() {
        BaritoneSession.Saved saved = new BaritoneSession.Saved("#", BaritoneSession.Mode.MINE,
            values(true, false, false, true, true));
        assertEquals(List.of("#set allowBreak false", "#set allowPlace true", "#set allowWaterBucketFall true",
            "#set censorCoordinates true", "#set censorRanCommands false"), BaritoneSession.restoration(saved));
    }

    @Test
    void defaultsResetsOnlyTheThreeAndGivesTheCensorPairBack() {
        BaritoneSession.Saved saved = new BaritoneSession.Saved(">", BaritoneSession.Mode.DEFAULTS,
            values(true, false, false, true, true));
        assertEquals(List.of(">set reset allowBreak", ">set reset allowPlace", ">set reset allowWaterBucketFall",
            ">set censorCoordinates true", ">set censorRanCommands false"), BaritoneSession.restoration(saved));
    }

    @Test
    void theSavedFileRoundTrips() {
        BaritoneSession.Saved saved = new BaritoneSession.Saved("# ", BaritoneSession.Mode.DEFAULTS,
            values(true, false, false, true, true));
        assertEquals(List.of("xploits-printer-baritone 1", "prefix # ", "mode DEFAULTS", "censorCoordinates true",
            "censorRanCommands false", "allowBreak false", "allowPlace true", "allowWaterBucketFall true"), saved.toLines());
        assertEquals(Optional.of(saved), BaritoneSession.Saved.fromLines(saved.toLines()));
    }

    @Test
    void aDamagedSavedFileIsRefused() {
        List<String> good = new BaritoneSession.Saved("#", BaritoneSession.Mode.MINE, values(false, false, true, true, true)).toLines();
        assertTrue(BaritoneSession.Saved.fromLines(good).isPresent());
        for (int line = 0; line < good.size(); line++) {
            if (line == 1) continue; // "prefix #x" is another prefix, and a valid one
            List<String> broken = new java.util.ArrayList<>(good);
            broken.set(line, broken.get(line) + "x");
            assertEquals(Optional.empty(), BaritoneSession.Saved.fromLines(broken), "line " + line);
        }
        assertEquals(Optional.empty(), BaritoneSession.Saved.fromLines(good.subList(0, 7)));
        assertEquals(Optional.empty(), BaritoneSession.Saved.fromLines(List.of()));
    }

    @Test
    void theBaritoneDefaultsAreTheVerifiedOnes() {
        assertEquals(Map.of("allowBreak", true, "allowPlace", true, "allowWaterBucketFall", true,
            "censorCoordinates", false, "censorRanCommands", false), BaritoneSession.DEFAULTS);
        assertEquals(List.of("censorCoordinates", "censorRanCommands", "allowBreak", "allowPlace", "allowWaterBucketFall"),
            BaritoneSession.CHANGED);
    }
}
