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
    /** R3-14: the fight situations where OUR player moves too, after {@link #FIGHTS}. */
    private static final List<String> SELF_FIGHTS = List.of("self-circle", "self-strafe");
    /** Task A3: the real crystal-PvP fights (fight mode) that run before {@code city}, then after it — the
     * same {@code FIGHTS_BEFORE_CITY}/{@code FIGHTS_AFTER_CITY} split {@code Scenarios.java} itself uses,
     * mirrored here since {@code city} is its own scenario class ({@code CityMeasure}). */
    private static final List<String> REAL_FIGHTS_BEFORE_CITY = List.of("exchange", "hole-standoff");
    private static final List<String> REAL_FIGHTS_AFTER_CITY = List.of("near-death", "near-death-totem");

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
        for (String f : SELF_FIGHTS) all.add(new Mirrored("ca-" + f + "-regen", true, null, null));
        for (String f : SELF_FIGHTS) all.add(new Mirrored("capp-" + f + "-regen", true, SAFE, "ca-" + f + "-regen"));
        for (RiskLevel level : List.of(BALANCED, AGGRESSIVE)) {
            String prefix = "capp-" + level.name().toLowerCase(java.util.Locale.ROOT) + "-";
            for (String f : SELF_FIGHTS) all.add(new Mirrored(prefix + f + "-regen", true, level, "ca-" + f + "-regen"));
        }
        // Task A3: the real fights, last, ca-<f> then capp-<f> (Safe) then capp-<level>-<f> for each other
        // level, for every fight of the group, exactly as every block above; city between the two groups.
        addRealFights(all, REAL_FIGHTS_BEFORE_CITY);
        all.add(new Mirrored("ca-city", true, null, null));
        all.add(new Mirrored("capp-city", true, SAFE, "ca-city"));
        for (RiskLevel level : List.of(BALANCED, AGGRESSIVE)) {
            String prefix = "capp-" + level.name().toLowerCase(java.util.Locale.ROOT) + "-";
            all.add(new Mirrored(prefix + "city", true, level, "ca-city"));
        }
        addRealFights(all, REAL_FIGHTS_AFTER_CITY);
        return all;
    }

    private static void addRealFights(List<Mirrored> all, List<String> fights) {
        for (String f : fights) all.add(new Mirrored("ca-" + f, true, null, null));
        for (String f : fights) all.add(new Mirrored("capp-" + f, true, SAFE, "ca-" + f));
        for (RiskLevel level : List.of(BALANCED, AGGRESSIVE)) {
            String prefix = "capp-" + level.name().toLowerCase(java.util.Locale.ROOT) + "-";
            for (String f : fights) all.add(new Mirrored(prefix + f, true, level, "ca-" + f));
        }
    }

    private static List<String> names(List<Mirrored> scenarios) {
        return scenarios.stream().map(Mirrored::name).toList();
    }

    @Test
    void theBenchHas72Scenarios() {
        assertEquals(72, scenarios().size());
        assertEquals(72, names(scenarios()).stream().distinct().count());
    }

    @Test
    void theEverydayRunPlaysTheChecksMeteorBalancedAndTheDefenseAttacker() {
        List<Mirrored> played = scenarios().stream().filter(s -> Profile.EVERYDAY.plays(s.measure(), s.risk())).toList();
        List<String> expected = new ArrayList<>(CHECKS);
        expected.addAll(List.of("ca-still", "ca-circler", "ca-defender", "ca-still-regen", "ca-circler-regen",
            "capp-balanced-still", "capp-balanced-circler", "capp-balanced-still-regen", "capp-balanced-circler-regen",
            "defense-attacker", "ca-above-regen", "ca-below-regen", "ca-approach-regen", "ca-strafe-regen",
            "capp-balanced-above-regen", "capp-balanced-below-regen", "capp-balanced-approach-regen",
            "capp-balanced-strafe-regen", "ca-self-circle-regen", "ca-self-strafe-regen",
            "capp-balanced-self-circle-regen", "capp-balanced-self-strafe-regen",
            // Task A3: the real fights' Meteor and Balanced pairs (Safe and Aggressive are experimental).
            "ca-exchange", "ca-hole-standoff", "capp-balanced-exchange", "capp-balanced-hole-standoff",
            "ca-city", "capp-balanced-city", "ca-near-death", "ca-near-death-totem",
            "capp-balanced-near-death", "capp-balanced-near-death-totem"));
        assertEquals(expected, names(played));
        assertEquals(41, played.size());
    }

    @Test
    void theEverydayRunSkipsSafeAndAggressiveOnly() {
        List<Mirrored> skipped = scenarios().stream().filter(s -> !Profile.EVERYDAY.plays(s.measure(), s.risk())).toList();
        assertEquals(31, skipped.size());
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
        assertEquals(72, scenarios().stream().filter(s -> Profile.FULL.plays(s.measure(), s.risk())).count());
    }

    @Test
    void theCacheServesExactlySixteenMeteorMeasures() {
        List<String> cacheable = names(scenarios().stream()
            .filter(s -> MeteorCache.cacheable(s.measure(), s.name(), s.risk() != null, s.compareWith() != null)).toList());
        assertEquals(List.of("ca-still", "ca-circler", "ca-defender", "ca-still-regen", "ca-circler-regen",
            "ca-above-regen", "ca-below-regen", "ca-approach-regen", "ca-strafe-regen", "ca-self-circle-regen",
            "ca-self-strafe-regen",
            // Task A3: the real fights' own Meteor measures are cacheable the same way (measure, ca-*, no risk,
            // no compare).
            "ca-exchange", "ca-hole-standoff", "ca-city", "ca-near-death", "ca-near-death-totem"), cacheable);
    }

    /**
     * SHA-256 of {@code Scenarios.java}, line endings normalized, when this mirror was last checked against the
     * real {@code Scenarios.all()} (task A3: the real fights added, last, city between the two
     * {@code addRealFights} groups; every earlier scenario's name, kind, level and twin unchanged).
     */
    private static final String SCENARIOS_FINGERPRINT = "03d1a36aef4836ab8d028f23abdabe1edcef799bfc769f7e09200ad15ccd1504";

    /**
     * SHA-256 of {@code CrystalAuraMeasure.java}, same normalization (R3-17). Its factories ({@code meteor},
     * {@code plusPlus}, {@code healing}, {@code movingSelf}) are what actually set each scenario's {@code
     * measure} kind, {@code risk} level and {@code compareWith} twin: this mirror hardcodes their result, so
     * a change to what those factories wire up would not touch {@code Scenarios.java} and would slip past
     * {@link #SCENARIOS_FINGERPRINT} alone.
     */
    private static final String CRYSTAL_AURA_MEASURE_FINGERPRINT =
        "2a4b56ecb43ef84b4c83b38a046a339b05a0ec04009e6fa74523b449a1204c36";

    /**
     * SHA-256 of {@code FightMeasure.java}, same normalization (task A3). Its factories ({@code meteor},
     * {@code plusPlus}, {@code startingLow}) set the {@code measure} kind, {@code risk} level and {@code
     * compareWith} twin of every real fight but {@code city} ({@link #REAL_FIGHTS_BEFORE_CITY}/{@link
     * #REAL_FIGHTS_AFTER_CITY}), the same role {@link #CRYSTAL_AURA_MEASURE_FINGERPRINT} plays for the older
     * scenarios.
     */
    private static final String FIGHT_MEASURE_FINGERPRINT = "0782e5b62964bacb17fea15dff63974e24cd9e6b52568929bbe4afbefba6fb8f";

    /**
     * SHA-256 of {@code CityMeasure.java}, same normalization (task A3). Its factories ({@code meteor},
     * {@code plusPlus}) set {@code city}'s own {@code measure} kind, {@code risk} level and {@code compareWith}
     * twin, the same role {@link #FIGHT_MEASURE_FINGERPRINT} plays for the other real fights.
     */
    private static final String CITY_MEASURE_FINGERPRINT = "7997d6bb757a760f350cc218fff6fef84cc3484f381dd5eb3eed2c2080edff8d";

    /**
     * SHA-256 of {@code RiskLevel.java}, same normalization (R3-17). This mirror derives its {@code
     * capp-<level>-} names from the enum's constant names ({@link RiskLevel#name()}, lowercased, mirroring
     * {@code Scenarios.java} and this file's own scenario builder) and hardcodes which levels are
     * experimental ({@link #theEverydayRunSkipsSafeAndAggressiveOnly}: Safe and Aggressive); a renamed,
     * reordered or added level would change what the real bench runs without touching either
     * {@code Scenarios.java} or {@code CrystalAuraMeasure.java}.
     */
    private static final String RISK_LEVEL_FINGERPRINT =
        "70a251df43ab0daa93fcd26608ca55a7ecf657429a8226f325c1be372f21f4df";

    @Test
    void theMirrorFollowsScenarios() throws IOException, NoSuchAlgorithmException {
        assertFingerprint(SCENARIOS_FINGERPRINT,
            Path.of("src", "gametest", "java", "com", "xploits", "bench", "Scenarios.java"));
        assertFingerprint(CRYSTAL_AURA_MEASURE_FINGERPRINT,
            Path.of("src", "gametest", "java", "com", "xploits", "bench", "CrystalAuraMeasure.java"));
        assertFingerprint(FIGHT_MEASURE_FINGERPRINT,
            Path.of("src", "gametest", "java", "com", "xploits", "bench", "FightMeasure.java"));
        assertFingerprint(CITY_MEASURE_FINGERPRINT,
            Path.of("src", "gametest", "java", "com", "xploits", "bench", "CityMeasure.java"));
        assertFingerprint(RISK_LEVEL_FINGERPRINT,
            Path.of("src", "main", "java", "com", "xploits", "pvp", "crystal", "core", "RiskLevel.java"));
    }

    /** One file's SHA-256, line endings normalized, checked against {@code expected}. */
    private static void assertFingerprint(String expected, Path file) throws IOException, NoSuchAlgorithmException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, fingerprint,
            file + " changed: bring ScenarioSelectionTest's mirror up to date, then its fingerprint");
    }
}
