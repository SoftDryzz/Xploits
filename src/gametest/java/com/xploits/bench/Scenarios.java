package com.xploits.bench;

import java.util.List;

/**
 * Every bench scenario, in the order a full run takes them: the CHECKs, then the MEASUREs. The {@code capp-}
 * MEASUREs run after all the {@code ca-} ones they are judged against (crystal-aura++ spec P6: every run
 * is a fresh world, so the order does not favour either side). The {@code -regen} pairs are the healing
 * twins of still and circler (crystal-aura++ spec, Round 2 (b)); the defender has none, since neither
 * aura places a crystal against it.
 */
public final class Scenarios {
    private Scenarios() {
    }

    public static List<Scenario> all() {
        CrystalAuraMeasure caStill = CrystalAuraMeasure.meteor("ca-still", Still::new);
        CrystalAuraMeasure caCircler = CrystalAuraMeasure.meteor("ca-circler", Circler::new);
        CrystalAuraMeasure cappStill = CrystalAuraMeasure.plusPlus("capp-still", "ca-still", Still::new);
        CrystalAuraMeasure cappCircler = CrystalAuraMeasure.plusPlus("capp-circler", "ca-circler", Circler::new);
        return List.of(new RecorderPopEnd(), new RecorderLost(), new RecorderOpponent(),
            new AutoPvpEngages(), new AutoPvpEngagesCapp(), new ProfileDefensive(), new AutoPvpAntiResources(),
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
            cappCircler.healing(),
            new DefenseAttacker());
    }

    /** The names of every scenario judged against another in the full bench: the pairs the recommendation needs. */
    public static List<String> judged() {
        return all().stream().filter(s -> s.compareWith().isPresent()).map(Scenario::name).toList();
    }
}
