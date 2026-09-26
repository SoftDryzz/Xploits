package com.xploits.bench;

import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.CrystalModule;
import com.xploits.pvp.core.ManagedModules;

import java.util.function.Predicate;

/**
 * CHECK {@code autopvp-engages-capp} (crystal-aura++ spec §4, P5): with {@code crystal-module} on
 * {@code xploits++}, auto-pvp drives crystal-aura++ and never Meteor's crystal-aura, and a change of the
 * setting mid-fight moves the drive from one aura to the other (the latch):
 * <ol>
 *   <li>{@value #RUN_TICKS} ticks on {@code xploits++}: some tick has auto-pvp engaged on the sparring,
 *   its plan enabling the logical crystal-aura and crystal-aura++ on; Meteor's crystal-aura is off on
 *   every tick.</li>
 *   <li>Switched to {@code meteor} while crystal-aura++ is on: crystal-aura++ is off within
 *   {@value #OFF_WITHIN} ticks and stays off, Meteor's crystal-aura is on within {@value #ON_WITHIN}.</li>
 *   <li>Switched back to {@code xploits++} while Meteor's is on: the same, the other way round.</li>
 *   <li>Auto-pvp turned off, one tick: both auras are off.</li>
 * </ol>
 *
 * <p>Standard loadout, recorder off, both auras off at T0, profile {@code balanced}, a Still sparring.
 */
final class AutoPvpEngagesCapp implements Scenario {
    private static final int RUN_TICKS = 200;
    /** A change is applied on auto-pvp's next tick (crystal-aura++ spec P5), which releases the old aura. */
    static final int OFF_WITHIN = 2;
    /** The ledger's debounce and the plan: the new aura comes on within this. */
    static final int ON_WITHIN = 40;
    /** How long each switch is watched: past {@link #ON_WITHIN}, so an old aura coming back shows. */
    private static final int SWITCH_TICKS = 60;
    /** How long to wait for the driven aura to be on before switching away from it. */
    private static final int WAIT_ON = 40;

    private AutoPvpScene scene;

    @Override
    public String name() {
        return "autopvp-engages-capp";
    }

    @Override
    public Kind kind() {
        return Kind.CHECK;
    }

    @Override
    public int seconds() {
        return 21;
    }

    @Override
    public void arrange(Bench bench) {
        scene = AutoPvpScene.arrange(bench, "balanced", CrystalModule.XPLOITS);
        bench.arena().loadout(false);
        bench.spawn(new Still());
    }

    @Override
    public Metrics act(Bench bench) {
        scene.start(bench, false);
        int engaged = 0;
        int planned = 0;
        int plusPlusOn = 0;
        int all = 0;
        int meteorOn = 0;
        int firstMeteorOn = -1;
        for (int i = 1; i <= RUN_TICKS; i++) {
            bench.ticks(1);
            AutoPvpScene.Look look = scene.look(bench);
            boolean inFight = look.state() != null && look.state() != CombatState.NO_COMBAT;
            boolean onSparring = Sparring.NAME.equals(look.target());
            if (inFight && onSparring) engaged++;
            if (look.planEnablesAura()) planned++;
            if (look.plusPlusOn()) plusPlusOn++;
            if (inFight && onSparring && look.planEnablesAura() && look.plusPlusOn()) all++;
            if (look.auraOn()) {
                meteorOn++;
                if (firstMeteorOn < 0) firstMeteorOn = i;
            }
        }
        Bench.check(meteorOn == 0, "Meteor's crystal-aura was on for " + meteorOn + " ticks with crystal-module xploits++"
            + " (first at tick " + firstMeteorOn + ")");
        Bench.check(all > 0, "no tick had auto-pvp engaged on the sparring with crystal-aura planned and crystal-aura++ on"
            + " (ticks: engaged " + engaged + ", planned " + planned + ", crystal-aura++ on " + plusPlusOn + ")");

        waitOn(bench, AutoPvpScene.Look::plusPlusOn, "crystal-aura++");
        Switch toMeteor = switchTo(bench, CrystalModule.METEOR);
        toMeteor.check("crystal-aura++", "Meteor's crystal-aura", "meteor");

        waitOn(bench, AutoPvpScene.Look::auraOn, "Meteor's crystal-aura");
        Switch back = switchTo(bench, CrystalModule.XPLOITS);
        back.check("Meteor's crystal-aura", "crystal-aura++", "xploits++");

        bench.onClient(client -> scene.autoPvp.disable());
        bench.ticks(1);
        Bench.check(!bench.fromClient(client -> AutoPvpScene.on(ManagedModules.CRYSTAL_AURA)),
            "Meteor's crystal-aura is still on after auto-pvp was turned off");
        Bench.check(!bench.fromClient(client -> AutoPvpScene.plusPlusOn()),
            "crystal-aura++ is still on after auto-pvp was turned off");
        bench.finish();
        return Metrics.none();
    }

    /** Waits, at most {@value #WAIT_ON} ticks, until the aura auto-pvp drives is on: a switch must find it on. */
    private void waitOn(Bench bench, Predicate<AutoPvpScene.Look> on, String aura) {
        if (on.test(scene.look(bench))) return;
        for (int i = 1; i <= WAIT_ON; i++) {
            bench.ticks(1);
            if (on.test(scene.look(bench))) return;
        }
        Bench.check(false, aura + " was not on to switch away from (waited " + WAIT_ON + " ticks)");
    }

    /**
     * After a change of {@code crystal-module} to {@code to}, counted in ticks from the change: when the
     * old aura went off, how many ticks it was on again after that, and when the new one came on (-1: never).
     */
    private record Switch(int oldOff, int oldBackOn, int newOn) {
        void check(String old, String now, String to) {
            Bench.check(oldOff >= 1 && oldOff <= OFF_WITHIN, oldOff < 0
                ? old + " never went off after the switch to " + to
                : old + " went off only at tick " + oldOff + " after the switch to " + to + " (at most " + OFF_WITHIN + ")");
            Bench.check(oldBackOn == 0, old + " came back on for " + oldBackOn + " ticks after the switch to " + to);
            Bench.check(newOn >= 1 && newOn <= ON_WITHIN, newOn < 0
                ? now + " never came on after the switch to " + to
                : now + " came on only at tick " + newOn + " after the switch to " + to + " (at most " + ON_WITHIN + ")");
        }
    }

    private Switch switchTo(Bench bench, CrystalModule to) {
        bench.setting(scene.autoPvp, "General", "crystal-module", to);
        boolean toMeteor = to == CrystalModule.METEOR;
        int oldOff = -1;
        int oldBackOn = 0;
        int newOn = -1;
        for (int i = 1; i <= SWITCH_TICKS; i++) {
            bench.ticks(1);
            AutoPvpScene.Look look = scene.look(bench);
            boolean oldOn = toMeteor ? look.plusPlusOn() : look.auraOn();
            boolean nowOn = toMeteor ? look.auraOn() : look.plusPlusOn();
            if (!oldOn && oldOff < 0) oldOff = i;
            else if (oldOn && oldOff > 0) oldBackOn++;
            if (nowOn && newOn < 0) newOn = i;
        }
        return new Switch(oldOff, oldBackOn, newOn);
    }
}
