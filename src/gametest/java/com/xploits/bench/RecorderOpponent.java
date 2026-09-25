package com.xploits.bench;

import com.xploits.pvp.recorder.FightRecorder;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord;

import java.util.List;

/**
 * CHECK {@code recorder-opponent} (spec {@code 2026-09-25-ingame-bench}, §Scenarios): crystals a player
 * explodes on you are put down to that player: the record names {@code Sparring} as an opponent who
 * hurt you, and its damage events say by whom.
 *
 * <p>Standard loadout, recorder on, an Attacker that attacks at T0+20 and T0+60 and stops at T0+80.
 */
final class RecorderOpponent implements Scenario {
    private static final int STOP_TICK = 80;

    @Override
    public String name() {
        return "recorder-opponent";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 5;
    }

    @Override
    public void arrange(Bench bench) {
        bench.meteor(FightRecorder.class);
        bench.arena().loadout(false);
        bench.spawn(new Attacker(STOP_TICK));
    }

    @Override
    public Metrics act(Bench bench) {
        bench.start(true);
        bench.ticks(seconds() * 20);

        List<FightRecord> fights = bench.finish();
        Bench.check(fights.size() == 1, "expected one new fight record, found " + fights.size());
        FightRecord fight = fights.getFirst();
        FightRecord.Opponent sparring = fight.opponents().stream()
            .filter(o -> Sparring.NAME.equals(o.name())).findFirst().orElse(null);
        Bench.check(sparring != null, "the record does not name the sparring as an opponent");
        Bench.check(sparring.damageToYou() > 0, "the record says the sparring did no damage to you");
        boolean byName = fight.damage().stream()
            .anyMatch(d -> d.by() == AttackerKind.PLAYER && Sparring.NAME.equals(d.attacker()));
        Bench.check(byName, "no damage event is put down to the sparring");
        return Metrics.none();
    }
}
