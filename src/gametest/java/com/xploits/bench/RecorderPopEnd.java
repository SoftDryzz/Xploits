package com.xploits.bench;

import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.FightOutcome;
import com.xploits.pvp.recorder.core.FightRecord;

import java.util.List;

/**
 * CHECK {@code recorder-pop-end} (spec {@code 2026-09-25-ingame-bench}, §Scenarios): a fight with one
 * pop that goes quiet ends ENDED with the pop counted and the totem gone from the end totals. It guards
 * the 0.6.1 end-totals fix: the inventory lags the pop, so totals frozen on the pop's own tick would
 * still count the used totem.
 *
 * <p>Bare loadout, recorder on. t=0: a crystal ten blocks away explodes (the fight opens). t=1 s:
 * {@code /damage} for 30 pops the only totem. The fight goes quiet after 20 s and ends on its own.
 */
final class RecorderPopEnd implements Scenario {
    private static final int POP_AT = 20;
    private static final int POP_DAMAGE = 30;

    @Override
    public String name() {
        return "recorder-pop-end";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 25;
    }

    @Override
    public void arrange(Bench bench) {
        bench.meteor(FightRecorder.class);
        bench.arena().bare();
    }

    @Override
    public Metrics act(Bench bench) {
        bench.start(true);
        bench.arena().explodeCrystal(10, 0, 0);
        bench.ticks(POP_AT);
        bench.command("damage " + Bench.PLAYER + " " + POP_DAMAGE + " minecraft:generic");
        bench.ticks(seconds() * 20 - POP_AT);

        List<FightRecord> fights = bench.finish();
        Bench.check(fights.size() == 1, "expected one new fight record, found " + fights.size());
        FightRecord fight = fights.getFirst();
        Bench.check(fight.outcome() == FightOutcome.ENDED, "the fight ended " + fight.outcome() + ", not ENDED");
        FightRecord.SelfTotals self = fight.self();
        Bench.check(self.pops() == 1, "the record counts " + self.pops() + " pops, not 1");
        Bench.check(self.totemsEnd() == 0, "the record ends with " + self.totemsEnd() + " totems, not 0");
        Bench.check(!self.offhandTotemEnd(), "the record ends with a totem in the offhand");
        return Metrics.none();
    }
}
