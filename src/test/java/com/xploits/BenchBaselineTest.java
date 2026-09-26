package com.xploits;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The committed bench baseline holds numbers only (in-game bench spec, §Report and baseline): it parses,
 * and no key at any depth names a position. A missing file passes: there is nothing to compare yet.
 */
class BenchBaselineTest {
    private static final Path BASELINE = Path.of("bench", "baseline.json");
    private static final Set<String> POSITION_KEYS = Set.of("x", "y", "z", "pos", "position", "coord", "dimension");

    @Test
    void baselineHasNoPositionKeys() throws IOException {
        if (!Files.exists(BASELINE)) return;
        JsonElement root = JsonParser.parseString(Files.readString(BASELINE, StandardCharsets.UTF_8));
        assertEquals(List.of(), positionKeys(root), "the bench baseline must not hold a position");
    }

    @Test
    void positionKeysAreFoundAtAnyDepth() {
        JsonElement json = JsonParser.parseString("""
            {"schema": 1, "scenarios": {"ca-still": {"damage_dealt": 12.5, "pos": 3}},
             "list": [{"deep": [{"Z": 1}]}]}""");
        assertEquals(List.of("scenarios.ca-still.pos", "list[0].deep[0].Z"), positionKeys(json));
    }

    /** Every key named like a position, as a path from the root. */
    static List<String> positionKeys(JsonElement root) {
        List<String> found = new ArrayList<>();
        walk(root, "", found);
        return found;
    }

    private static void walk(JsonElement element, String path, List<String> found) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                if (POSITION_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) found.add(child);
                walk(entry.getValue(), child, found);
            }
        } else if (element.isJsonArray()) {
            for (int i = 0; i < element.getAsJsonArray().size(); i++) {
                walk(element.getAsJsonArray().get(i), path + "[" + i + "]", found);
            }
        }
    }
}
