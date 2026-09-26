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
 * <p>The {@code capp-X} scenarios run crystal-aura++ at its default {@code risk} level, Safe. Each other level
 * the bench measures, Balanced and Aggressive (R2-5), runs as {@code capp-<level>-X} for X in still, circler,
 * still-regen and circler-regen, judged against the same {@code ca-X}, after all the Safe ones.
 *
 * <p>The fight situations (R3-5) come last, after everything above, in the same order: {@code ca-<s>-regen},
 * then {@code capp-<s>-regen} at Safe, then each other level, for {@code <s>} in {@link #FIGHTS}' order. Each
 * runs only with healing on: an enemy above us ({@link Above}), below us ({@link Below}), closing in and
 * backing off ({@link Approach}), and strafing ({@link Strafe}).
 */
public final class Scenarios {
    private Scenarios() {
    }

    /** The levels measured besides the default, Safe, each on the still and circler pairs with and without healing. */
    static final List<RiskLevel> OTHER_LEVELS = List.of(RiskLevel.BALANCED, RiskLevel.AGGRESSIVE);

    /** A fight situation (R3-5): its name, {@code <s>} in the scenarios' names, and its script. */
    private record Fight(String name, Supplier<Script> script) {
    }

    /** The fight situations, in the order they run. */
    private static final List<Fight> FIGHTS = List.of(new Fight("above", Above::new), new Fight("below", Below::new),
        new Fight("approach", Approach::new), new Fight("strafe", Strafe::new));

    public static List<Scenario> all() {
        CrystalAuraMeasure caStill = CrystalAuraMeasure.meteor("ca-still", Still::new);
        CrystalAuraMeasure caCircler = CrystalAuraMeasure.meteor("ca-circler", Circler::new);
        CrystalAuraMeasure cappStill = CrystalAuraMeasure.plusPlus("capp-still", "ca-still", Still::new);
        CrystalAuraMeasure cappCircler = CrystalAuraMeasure.plusPlus("capp-circler", "ca-circler", Circler::new);
        List<Scenario> all = new ArrayList<>(List.of(new RecorderPopEnd(), new RecorderLost(), new RecorderOpponent(),
            new AutoPvpEngages(), new AutoPvpEngagesCapp(), new CappBudgetOffParity(), new ProfileDefensive(),
            new AutoPvpAntiResources(),
            new Panel(),
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
        return List.copyOf(all);
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
