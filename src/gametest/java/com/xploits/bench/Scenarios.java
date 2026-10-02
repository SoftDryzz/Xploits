package com.xploits.bench;

import com.xploits.pvp.crystal.core.RiskLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every bench scenario, in the order a full run takes them: the CHECKs, then the MEASUREs. The {@code capp-}
 * MEASUREs run after all the {@code ca-} ones they are judged against (crystal-aura++ spec P6: every run
 * is a fresh world, so the order does not favour either side). The {@code -regen} pairs are the healing
 * twins of still and circler (crystal-aura++ spec, Round 2 (b)); the defender has none, since neither
 * aura places a crystal against it.
 *
 * <p>The {@code capp-X} scenarios run crystal-aura++ at Safe (R2-5's original level for the bench; the
 * {@code risk} setting's own default is Balanced since R3-8). Each other level the bench measures, Balanced and
 * Aggressive (R2-5), runs as {@code capp-<level>-X} for X in still, circler,
 * still-regen and circler-regen, judged against the same {@code ca-X}, after all the Safe ones.
 *
 * <p>The fight situations (R3-5) come next, after everything above, in the same order: {@code ca-<s>-regen},
 * then {@code capp-<s>-regen} at Safe, then each other level, for {@code <s>} in {@link #FIGHTS}' order. Each
 * runs only with healing on: an enemy above us ({@link Above}), below us ({@link Below}), closing in and
 * backing off ({@link Approach}), and strafing ({@link Strafe}).
 *
 * <p>Last come the fight situations where OUR player moves too (R3-14), the same way: {@code ca-<s>-regen},
 * {@code capp-<s>-regen} at Safe, then each other level, for {@code <s>} in {@link #SELF_FIGHTS}' order, each
 * with healing on too. Self-circle walks a circle around our own start block while the sparring stands still
 * ({@link Still}, {@link SelfCircleMotion}); self-strafe zig-zags sideways while the sparring circles
 * ({@link Circler}, {@link SelfStrafeMotion}).
 */
public final class Scenarios {
    private Scenarios() {
    }

    /** The levels measured besides Safe, each on the still and circler pairs with and without healing. */
    static final List<RiskLevel> OTHER_LEVELS = List.of(RiskLevel.BALANCED, RiskLevel.AGGRESSIVE);

    /** A fight situation (R3-5): its name, {@code <s>} in the scenarios' names, and its script. */
    private record Fight(String name, Supplier<Script> script) {
    }

    /** The fight situations, in the order they run. */
    private static final List<Fight> FIGHTS = List.of(new Fight("above", Above::new), new Fight("below", Below::new),
        new Fight("approach", Approach::new), new Fight("strafe", Strafe::new));

    /**
     * A fight situation where OUR player moves too (R3-14): its name, {@code <s>} in the scenarios' names, the
     * sparring's own script, and our own movement.
     */
    private record SelfFight(String name, Supplier<Script> script, Supplier<SelfMotion> selfMotion) {
    }

    /** The self-moving fight situations, in the order they run, after {@link #FIGHTS}'. */
    private static final List<SelfFight> SELF_FIGHTS = List.of(
        new SelfFight("self-circle", Still::new, SelfCircleMotion::new),
        new SelfFight("self-strafe", Circler::new, SelfStrafeMotion::new));

    /**
     * Task A3: a real crystal-PvP fight (fight mode — finite totems, gapples after every pop, a win/loss/draw
     * outcome, task A1): its name, {@code <f>} in the scenarios' names, its script, and, only for the
     * {@code near-death} pair, whether the opponent holds a totem ({@code null}: no starting-health setup at
     * all, the other two fights).
     */
    private record RealFight(String name, Supplier<Script> script, Boolean opponentHoldsTotem) {
    }

    /** {@code exchange} and {@code hole-standoff}: the real fights that run before {@code city}. */
    private static final List<RealFight> FIGHTS_BEFORE_CITY = List.of(
        new RealFight("exchange", Fights::exchange, null),
        new RealFight("hole-standoff", Fights::holeStandoff, null));

    /** {@code near-death} and its {@code near-death-totem} variant: the real fights that run after {@code city}. */
    private static final List<RealFight> FIGHTS_AFTER_CITY = List.of(
        new RealFight("near-death", Fights::nearDeath, false),
        new RealFight("near-death-totem", Fights::nearDeathTotem, true));

    public static List<Scenario> all() {
        CrystalAuraMeasure caStill = CrystalAuraMeasure.meteor("ca-still", Still::new);
        CrystalAuraMeasure caCircler = CrystalAuraMeasure.meteor("ca-circler", Circler::new);
        CrystalAuraMeasure cappStill = CrystalAuraMeasure.plusPlus("capp-still", "ca-still", Still::new);
        CrystalAuraMeasure cappCircler = CrystalAuraMeasure.plusPlus("capp-circler", "ca-circler", Circler::new);
        List<Scenario> all = new ArrayList<>(List.of(new RecorderPopEnd(), new RecorderLost(), new RecorderOpponent(),
            new AutoPvpEngages(), new AutoPvpEngagesCapp(), new CappBudgetOffParity(), new ProfileDefensive(),
            new AutoPvpAntiResources(),
            new Panel(),
            new ExposureCoverProbe(),
            new BaritoneNet(),
            new RestockNoLitematica(),
            new RestockTrips(),
            new RestockExcludesTravel(),
            caStill,
            caCircler,
            CrystalAuraMeasure.meteor("ca-defender", Defender::new),
            caStill.healing(),
            caCircler.healing(),
            cappStill,
            cappCircler,
            CrystalAuraMeasure.plusPlus("capp-defender", "ca-defender", Defender::new),
            cappStill.healing(),
            cappCircler.healing()));
        for (RiskLevel level : OTHER_LEVELS) {
            String prefix = "capp-" + level.toString().toLowerCase(Locale.ROOT) + "-";
            CrystalAuraMeasure still = CrystalAuraMeasure.plusPlus(prefix + "still", "ca-still", Still::new, level);
            CrystalAuraMeasure circler = CrystalAuraMeasure.plusPlus(prefix + "circler", "ca-circler", Circler::new, level);
            all.addAll(List.of(still, circler, still.healing(), circler.healing()));
        }
        all.add(new DefenseAttacker());
        for (Fight fight : FIGHTS) all.add(CrystalAuraMeasure.meteor("ca-" + fight.name(), fight.script()).healing());
        for (Fight fight : FIGHTS) {
            all.add(CrystalAuraMeasure.plusPlus("capp-" + fight.name(), "ca-" + fight.name(), fight.script()).healing());
        }
        for (RiskLevel level : OTHER_LEVELS) {
            String prefix = "capp-" + level.toString().toLowerCase(Locale.ROOT) + "-";
            for (Fight fight : FIGHTS) {
                all.add(CrystalAuraMeasure.plusPlus(prefix + fight.name(), "ca-" + fight.name(), fight.script(), level)
                    .healing());
            }
        }
        for (SelfFight fight : SELF_FIGHTS) {
            all.add(CrystalAuraMeasure.meteor("ca-" + fight.name(), fight.script()).healing().movingSelf(fight.selfMotion()));
        }
        for (SelfFight fight : SELF_FIGHTS) {
            all.add(CrystalAuraMeasure.plusPlus("capp-" + fight.name(), "ca-" + fight.name(), fight.script())
                .healing().movingSelf(fight.selfMotion()));
        }
        for (RiskLevel level : OTHER_LEVELS) {
            String prefix = "capp-" + level.toString().toLowerCase(Locale.ROOT) + "-";
            for (SelfFight fight : SELF_FIGHTS) {
                all.add(CrystalAuraMeasure.plusPlus(prefix + fight.name(), "ca-" + fight.name(), fight.script(), level)
                    .healing().movingSelf(fight.selfMotion()));
            }
        }
        // Task B2 fix round 1: real cover for the self-budget (Cover): ca-cover, capp-cover (Safe), then each other
        // level, not healing (the sparring stands still, so the run settles), before the real fights.
        all.add(CrystalAuraMeasure.meteor("ca-cover", Cover::new));
        all.add(CrystalAuraMeasure.plusPlus("capp-cover", "ca-cover", Cover::new));
        for (RiskLevel level : OTHER_LEVELS) {
            String prefix = "capp-" + level.toString().toLowerCase(Locale.ROOT) + "-";
            all.add(CrystalAuraMeasure.plusPlus(prefix + "cover", "ca-cover", Cover::new, level));
        }
        // 0.7.2: a jump stopped by a ceiling (RoofJump, RoofJumpMotion): ca-roof-jump-regen, capp-roof-jump-regen
        // (Safe), then each other level, healing on and our own player jumping, right after cover.
        all.add(CrystalAuraMeasure.meteor("ca-roof-jump", RoofJump::new).healing().movingSelf(RoofJumpMotion::new));
        all.add(CrystalAuraMeasure.plusPlus("capp-roof-jump", "ca-roof-jump", RoofJump::new).healing()
            .movingSelf(RoofJumpMotion::new));
        for (RiskLevel level : OTHER_LEVELS) {
            String prefix = "capp-" + level.toString().toLowerCase(Locale.ROOT) + "-";
            all.add(CrystalAuraMeasure.plusPlus(prefix + "roof-jump", "ca-roof-jump", RoofJump::new, level).healing()
                .movingSelf(RoofJumpMotion::new));
        }
        // Task A3: the real crystal-PvP fights, last, in the brief's own order (exchange, hole-standoff, city,
        // near-death, near-death-totem); city is its own scenario class ({@link CityMeasure}, since auto-pvp
        // drives our own side there) but keeps the same ca-*, then capp-* Safe, then each other level shape
        // every earlier block here uses.
        addRealFights(all, FIGHTS_BEFORE_CITY);
        addCityFight(all);
        addRealFights(all, FIGHTS_AFTER_CITY);
        return List.copyOf(all);
    }

    /** {@code ca-<f>}, then {@code capp-<f>} (Safe), then {@code capp-<level>-<f>} for each of
     * {@link #OTHER_LEVELS}, for every fight in {@code fights}, in that order. */
    private static void addRealFights(List<Scenario> all, List<RealFight> fights) {
        for (RealFight fight : fights) all.add(startingLow(FightMeasure.meteor("ca-" + fight.name(), fight.script()), fight));
        for (RealFight fight : fights) {
            all.add(startingLow(FightMeasure.plusPlus("capp-" + fight.name(), "ca-" + fight.name(), fight.script()), fight));
        }
        for (RiskLevel level : OTHER_LEVELS) {
            String prefix = "capp-" + level.toString().toLowerCase(Locale.ROOT) + "-";
            for (RealFight fight : fights) {
                FightMeasure m = FightMeasure.plusPlus(prefix + fight.name(), "ca-" + fight.name(), fight.script(), level);
                all.add(startingLow(m, fight));
            }
        }
    }

    /** {@code fight}'s starting-health setup ({@link RealFight#opponentHoldsTotem}), or {@code measure}
     * unchanged when the fight has none (every real fight but {@code near-death}). */
    private static FightMeasure startingLow(FightMeasure measure, RealFight fight) {
        return fight.opponentHoldsTotem() == null ? measure : measure.startingLow(fight.opponentHoldsTotem());
    }

    /** {@code ca-city}, {@code capp-city} (Safe), then {@code capp-<level>-city} for each of {@link #OTHER_LEVELS}. */
    private static void addCityFight(List<Scenario> all) {
        all.add(CityMeasure.meteor("ca-city"));
        all.add(CityMeasure.plusPlus("capp-city", "ca-city"));
        for (RiskLevel level : OTHER_LEVELS) {
            String prefix = "capp-" + level.toString().toLowerCase(Locale.ROOT) + "-";
            all.add(CityMeasure.plusPlus(prefix + "city", "ca-city", level));
        }
    }

    /**
     * Every scenario judged against another in the full bench, with the {@code risk} level crystal-aura++ runs
     * at in it, in the bench's order: the pairs each level's recommendation needs.
     */
    public static Map<String, RiskLevel> judged() {
        Map<String, RiskLevel> judged = new LinkedHashMap<>();
        for (Scenario s : all()) {
            if (s.compareWith().isEmpty()) continue;
            judged.put(s.name(), s.risk().orElseThrow(() -> new IllegalStateException(s.name() + " is judged but has no risk level")));
        }
        return judged;
    }
}
