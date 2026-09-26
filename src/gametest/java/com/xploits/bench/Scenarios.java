package com.xploits.bench;

import java.util.List;

/**
 * Every bench scenario, in the order a full run takes them: the CHECKs, then the MEASUREs. The {@code capp-}
 * MEASUREs run after all the {@code ca-} ones they are judged against (crystal-aura++ spec P6: every run
 * is a fresh world, so the order does not favour either side).
 */
public final class Scenarios {
    private Scenarios() {
    }

    public static List<Scenario> all() {
        return List.of(new RecorderPopEnd(), new RecorderLost(), new RecorderOpponent(),
            new AutoPvpEngages(), new AutoPvpEngagesCapp(), new ProfileDefensive(), new AutoPvpAntiResources(),
            new Panel(),
            CrystalAuraMeasure.meteor("ca-still", Still::new),
            CrystalAuraMeasure.meteor("ca-circler", Circler::new),
            CrystalAuraMeasure.meteor("ca-defender", Defender::new),
            CrystalAuraMeasure.plusPlus("capp-still", "ca-still", Still::new),
            CrystalAuraMeasure.plusPlus("capp-circler", "ca-circler", Circler::new),
            CrystalAuraMeasure.plusPlus("capp-defender", "ca-defender", Defender::new),
            new DefenseAttacker());
    }
}
