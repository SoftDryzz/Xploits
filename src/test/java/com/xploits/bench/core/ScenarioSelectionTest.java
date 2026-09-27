package com.xploits.bench.core;

import com.xploits.pvp.crystal.core.RiskLevel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.xploits.pvp.crystal.core.RiskLevel.AGGRESSIVE;
import static com.xploits.pvp.crystal.core.RiskLevel.BALANCED;
import static com.xploits.pvp.crystal.core.RiskLevel.SAFE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The everyday run and the Meteor cache over the bench's real scenarios (R3-9, fix round 2): which ones the
 * everyday run plays and skips, and which ones the cache may serve. {@code Scenarios} is game code the unit tests
 * cannot load, so its list is mirrored here, in its order, and {@link #theMirrorFollowsScenarios} fails as soon as
 * {@code Scenarios.java} changes, until this mirror (and that test's fingerprint) is brought up to date.
 */
class ScenarioSelectionTest {
    /** What the selection reads of a scenario: its name, kind, crystal-aura++ level and Meteor twin. */
    private record Mirrored(String name, boolean measure, RiskLevel risk, String compareWith) {
    }

    private static final List<String> CHECKS = List.of("recorder-pop-end", "recorder-lost", "recorder-opponent",
        "autopvp-engages", "autopvp-engages-capp", "capp-budget-off-parity", "profile-defensive",
        "autopvp-anti-resources", "panel");
    private static final List<String> FIGHTS = List.of("above", "below", "approach", "strafe");

    /** {@code Scenarios.all()}, in its order. */
    private static List<Mirrored> scenarios() {
        List<Mirrored> all = new ArrayList<>();
        for (String check : CHECKS) all.add(new Mirrored(check, false, null, null));
        for (String s : List.of("ca-still", "ca-circler", "ca-defender", "ca-still-regen", "ca-circler-regen")) {
            all.add(new Mirrored(s, true, null, null));
        }
        for (String s : List.of("still", "circler", "defender", "still-regen", "circler-regen")) {
            all.add(new Mirrored("capp-" + s, true, SAFE, "ca-" + s));
        }
        for (RiskLevel level : List.of(BALANCED, AGGRESSIVE)) {
            String prefix = "capp-" + level.name().toLowerCase(java.util.Locale.ROOT) + "-";
            for (String s : List.of("still", "circler", "still-regen", "circler-regen")) {
                all.add(new Mirrored(prefix + s, true, level, "ca-" + s));
            }
        }
        all.add(new Mirrored("defense-attacker", true, null, null));
        for (String f : FIGHTS) all.add(new Mirrored("ca-" + f + "-regen", true, null, null));
        for (String f : FIGHTS) all.add(new Mirrored("capp-" + f + "-regen", true, SAFE, "ca-" + f + "-regen"));
        for (RiskLevel level : List.of(BALANCED, AGGRESSIVE)) {
            String prefix = "capp-" + level.name().toLowerCase(java.util.Locale.ROOT) + "-";
            for (String f : FIGHTS) all.add(new Mirrored(prefix + f + "-regen", true, level, "ca-" + f + "-regen"));
        }
        return all;
    }

    private static List<String> names(List<Mirrored> scenarios) {
        return scenarios.stream().map(Mirrored::name).toList();
    }

    @Test
    void theBenchHas44Scenarios() {
        assertEquals(44, scenarios().size());
        assertEquals(44, names(scenarios()).stream().distinct().count());
    }

    @Test
    void theEverydayRunPlaysTheChecksMeteorBalancedAndTheDefenseAttacker() {
        List<Mirrored> played = scenarios().stream().filter(s -> Profile.EVERYDAY.plays(s.measure(), s.risk())).toList();
        List<String> expected = new ArrayList<>(CHECKS);
        expected.addAll(List.of("ca-still", "ca-circler", "ca-defender", "ca-still-regen", "ca-circler-regen",
            "capp-balanced-still", "capp-balanced-circler", "capp-balanced-still-regen", "capp-balanced-circler-regen",
            "defense-attacker", "ca-above-regen", "ca-below-regen", "ca-approach-regen", "ca-strafe-regen",
            "capp-balanced-above-regen", "capp-balanced-below-regen", "capp-balanced-approach-regen",
            "capp-balanced-strafe-regen"));
        assertEquals(expected, names(played));
        assertEquals(27, played.size());
    }

    @Test
    void theEverydayRunSkipsSafeAndAggressiveOnly() {
        List<Mirrored> skipped = scenarios().stream().filter(s -> !Profile.EVERYDAY.plays(s.measure(), s.risk())).toList();
        assertEquals(17, skipped.size());
        for (Mirrored s : skipped) {
            boolean experimental = s.risk() == SAFE || s.risk() == AGGRESSIVE;
            assertEquals(true, s.measure() && experimental, s.name());
        }
        // Every Balanced pair's Meteor twin is played, so the Balanced recommendation judges all of its pairs.
        List<String> played = names(scenarios().stream().filter(s -> Profile.EVERYDAY.plays(s.measure(), s.risk())).toList());
        for (Mirrored s : scenarios()) {
            if (s.risk() == BALANCED) assertEquals(true, played.contains(s.compareWith()), s.name());
        }
    }

    @Test
    void theFullRunPlaysEveryScenario() {
        assertEquals(44, scenarios().stream().filter(s -> Profile.FULL.plays(s.measure(), s.risk())).count());
    }

    @Test
    void theCacheServesExactlyMeteorsNineMeasures() {
        List<String> cacheable = names(scenarios().stream()
            .filter(s -> MeteorCache.cacheable(s.measure(), s.name(), s.risk() != null, s.compareWith() != null)).toList());
        assertEquals(List.of("ca-still", "ca-circler", "ca-defender", "ca-still-regen", "ca-circler-regen",
            "ca-above-regen", "ca-below-regen", "ca-approach-regen", "ca-strafe-regen"), cacheable);
    }

    /**
     * SHA-256 of {@code Scenarios.java}, line endings normalized, when this mirror was last checked against the
     * real {@code Scenarios.all()} (R3-9 fix round 2: all 44 names, kinds, levels and twins matched).
     */
    private static final String SCENARIOS_FINGERPRINT = "5a9f874f0a8270c8fe046698097b9c5676974223bc3d343102e4e9aadab98e76";

    @Test
    void theMirrorFollowsScenarios() throws IOException, NoSuchAlgorithmException {
        Path file = Path.of("src", "gametest", "java", "com", "xploits", "bench", "Scenarios.java");
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(SCENARIOS_FINGERPRINT, fingerprint,
            "Scenarios.java changed: bring ScenarioSelectionTest's mirror up to date, then its fingerprint");
    }
}
