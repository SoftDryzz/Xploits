package com.xploits.bench;

import java.util.List;

/** Every bench scenario, in the order a full run takes them: the CHECKs, then the MEASUREs. */
public final class Scenarios {
    private Scenarios() {
    }

    public static List<Scenario> all() {
        return List.of(new RecorderPopEnd(), new RecorderLost(), new RecorderOpponent(),
            new AutoPvpEngages(), new ProfileDefensive(), new AutoPvpAntiResources(), new Panel(),
            new CrystalAuraMeasure("ca-still", Still::new),
            new CrystalAuraMeasure("ca-circler", Circler::new),
            new CrystalAuraMeasure("ca-defender", Defender::new),
            new DefenseAttacker());
    }
}
