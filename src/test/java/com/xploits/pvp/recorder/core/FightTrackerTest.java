package com.xploits.pvp.recorder.core;

import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.CombatEvent.SelfDamaged;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.FightRecord.ModuleChange;
import com.xploits.pvp.recorder.core.FightRecord.Opponent;
import com.xploits.pvp.recorder.core.FightRecord.PhaseChange;
import com.xploits.pvp.recorder.core.FightRecord.Sample;
import com.xploits.pvp.recorder.core.FightTracker.Step;
import com.xploits.shared.core.i18n.Catalog;
import com.xploits.shared.core.i18n.Language;
import com.xploits.shared.core.i18n.Msg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.xploits.pvp.recorder.core.Ticks.attack;
import static com.xploits.pvp.recorder.core.Ticks.broken;
import static com.xploits.pvp.recorder.core.Ticks.crystalBy;
import static com.xploits.pvp.recorder.core.Ticks.damaged;
import static com.xploits.pvp.recorder.core.Ticks.death;
import static com.xploits.pvp.recorder.core.Ticks.meleeBy;
import static com.xploits.pvp.recorder.core.Ticks.millis;
import static com.xploits.pvp.recorder.core.Ticks.ownCrystal;
import static com.xploits.pvp.recorder.core.Ticks.ownPop;
import static com.xploits.pvp.recorder.core.Ticks.placed;
import static com.xploits.pvp.recorder.core.Ticks.popOf;
import static com.xploits.pvp.recorder.core.Ticks.selfDied;
import static com.xploits.pvp.recorder.core.Ticks.spawnedNear;
import static com.xploits.pvp.recorder.core.Ticks.unattributed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FightTrackerTest {
    private static final Catalog EN = Catalog.load(Language.EN, p -> {
        throw new AssertionError(p);
    });

    private final FightTracker tracker = new FightTracker("0.5.0");
    private final Ticks ticks = new Ticks();

    private Step feed(CombatEvent... events) {
        return tracker.tick(ticks.next(events));
    }

    /** {@code n} ticks with no event, none of which may end the fight. */
    private void idle(int n) {
        for (int i = 0; i < n; i++) assertEquals(Optional.empty(), feed().finished(), "tick " + (ticks.tick() - 1));
    }

    private static List<String> render(List<Msg> live) {
        return live.stream().map(EN::render).toList();
    }

    /** Starts a fight on this tick by attacking Foo, who stands 5 blocks away; returns the tick. */
    private long attackFoo() {
        ticks.hostile("Foo", 5);
        long start = ticks.tick();
        feed(attack("Foo"));
        assertTrue(tracker.fighting());
        return start;
    }

    private FightRecord abortNow() {
        return tracker.abort(millis(ticks.tick())).orElseThrow();
    }

    // --- start -------------------------------------------------------------------------------------

    @Test
    void aCrystalHitFromAPlayerStartsAFightAndSaysWithWhom() {
        ticks.hostile("Foo", 5);
        feed();
        assertFalse(tracker.fighting());
        Step step = feed(crystalBy("Foo"));
        assertTrue(tracker.fighting());
        assertEquals(List.of("Fight started · Foo"), render(step.live()));
    }

    @Test
    void anyHitFromAPlayerStartsOneEvenIfItIsNotCombatDamage() {
        feed(new SelfDamaged(DamageKind.MAGIC, AttackerKind.PLAYER, "Foo", false));
        assertTrue(tracker.fighting());
    }

    @Test
    void combatDamageWithNoAttackerStartsOneWithNobody() {
        Step step = feed(unattributed(DamageKind.EXPLOSION));
        assertTrue(tracker.fighting());
        assertEquals(List.of("Fight started · nobody"), render(step.live()));
    }

    @Test
    void yourOwnCrystalHurtingYouStartsOne() {
        feed(ownCrystal());
        assertTrue(tracker.fighting());
    }

    @Test
    void mobsFallsAndFireAloneNeverStartOne() {
        ticks.hostile("Foo", 5);
        feed(unattributed(DamageKind.MOB));
        feed(unattributed(DamageKind.FALL));
        feed(unattributed(DamageKind.FIRE));
        feed(unattributed(DamageKind.OTHER));
        assertFalse(tracker.fighting());
    }

    @Test
    void anAllysHitNeverStartsOne() {
        feed(Ticks.allyCrystal("Ally"));
        assertFalse(tracker.fighting());
    }

    @Test
    void yourPopStartsOne() {
        feed(ownPop());
        assertTrue(tracker.fighting());
    }

    @Test
    void aPopStartsOneOnlyWhenThatPlayerIsWithinEngageRange() {
        ticks.hostile("Foo", 16.5);
        feed(popOf("Foo"));
        feed(popOf("Stranger"));
        assertFalse(tracker.fighting());
        ticks.hostile("Foo", 16.0);
        feed(popOf("Foo"));
        assertTrue(tracker.fighting());
    }

    @Test
    void placingOrBreakingCrystalsStartsOneOnlyWithAHostileWithinEngageRange() {
        feed(placed());
        feed(broken());
        ticks.hostile("Foo", 16.5);
        feed(placed());
        feed(broken());
        assertFalse(tracker.fighting());
        ticks.hostile("Foo", 16.0);
        feed(placed());
        assertTrue(tracker.fighting());

        FightTracker other = new FightTracker("0.5.0");
        Ticks more = new Ticks().hostile("Bar", 3);
        other.tick(more.next(broken()));
        assertTrue(other.fighting());
    }

    @Test
    void attackingOrHurtingAPlayerStartsOneAndSomeoneElseHurtingThemDoesNot() {
        feed(damaged("Foo", false));
        assertFalse(tracker.fighting());
        feed(damaged("Foo", true));
        assertTrue(tracker.fighting());

        FightTracker other = new FightTracker("0.5.0");
        other.tick(new Ticks().next(attack("Bar")));
        assertTrue(other.fighting());
    }

    @Test
    void autoPvpEngagingStartsOneWithoutAnnouncingIt() {
        ticks.autoPvp(CombatState.NO_COMBAT, CombatPosture.CALM, null);
        feed();
        assertFalse(tracker.fighting());
        ticks.autoPvp(CombatState.APPROACH, CombatPosture.CALM, "Foo");
        Step step = feed();
        assertTrue(tracker.fighting());
        assertEquals(List.of(), step.live());
        // The first exchange is what announces it.
        assertEquals(List.of("Fight started · Foo"), render(feed(meleeBy("Foo")).live()));
    }

    @Test
    void crystalsAppearingAndStrangersDyingDoNotStartOne() {
        ticks.hostile("Foo", 3);
        feed(spawnedNear());
        feed(death("Foo"));
        assertFalse(tracker.fighting());
    }

    @Test
    void deadTicksAndYourDeathWhileIdleAreIgnored() {
        ticks.alive(false);
        assertEquals(Optional.empty(), feed(crystalBy("Foo")).finished());
        assertFalse(tracker.fighting());
        ticks.alive(true);
        assertEquals(Optional.empty(), feed(selfDied()).finished());
        assertFalse(tracker.fighting());
    }

    // --- end ---------------------------------------------------------------------------------------

    @Test
    void dyingLosesTheFightWithTheLethalHitAndTheTotemsOfYourLastTickAlive() {
        ticks.hostile("Foo", 4).totems(3);
        feed();
        long start = ticks.tick();
        ticks.health(12);
        feed(crystalBy("Foo"));
        ticks.totems(2).offhandTotem(false);
        feed();
        // The tick you die on: the inventory is already gone and you are dead.
        long end = ticks.tick();
        ticks.alive(false).health(0).totems(0).offhandTotem(true);
        Step step = feed(crystalBy("Foo"), selfDied());

        FightRecord f = step.finished().orElseThrow();
        assertEquals(FightOutcome.LOST, f.outcome());
        assertFalse(tracker.fighting());
        assertEquals(millis(start), f.startedAt());
        assertEquals(millis(end), f.endedAt());
        assertEquals(List.of(
            new DamageEvent(start, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 20, 12, false),
            new DamageEvent(end, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 12, 0, true)), f.damage());
        assertEquals(20.0, f.self().damageTaken());
        assertEquals(3, f.self().totemsStart());
        assertEquals(2, f.self().totemsEnd());
        assertFalse(f.self().offhandTotemEnd());
        assertEquals(List.of(new Opponent("Foo", 0, false, 2, 20.0, 0)), f.opponents());
    }

    @Test
    void theDeathKeepsThePhaseAndModulesOfTheLastTickAlive() {
        ticks.hostile("Foo", 4).modules("crystal-aura", "surround")
            .autoPvp(CombatState.SURFACE, CombatPosture.THREATENED, "Foo");
        long start = attackFoo();
        idle(30);
        // auto-pvp wipes its plan and lets its modules go the moment you die.
        ticks.alive(false).modules().autoPvp(CombatState.NO_COMBAT, CombatPosture.CALM, null);
        FightRecord f = feed(selfDied()).finished().orElseThrow();
        assertEquals(List.of(new PhaseChange(0, CombatState.SURFACE, CombatPosture.THREATENED, "Foo")), f.phases());
        assertEquals(List.of(), f.moduleChanges());
        assertEquals(List.of("crystal-aura", "surround"), f.modulesAtStart());
        assertEquals(millis(start + 31), f.endedAt());
    }

    @Test
    void aOneShotFromIdleIsStillALostFight() {
        ticks.hostile("Foo", 4);
        feed();
        ticks.alive(false).health(0);
        FightRecord f = feed(crystalBy("Foo"), selfDied()).finished().orElseThrow();
        assertEquals(FightOutcome.LOST, f.outcome());
        assertEquals(List.of(new DamageEvent(ticks.tick() - 1, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 20, 0, true)),
            f.damage());
    }

    @Test
    void aDeadTickAfterALostFightNeverOpensAnother() {
        attackFoo();
        ticks.alive(false).health(0).totems(0);
        assertEquals(FightOutcome.LOST, feed(selfDied()).finished().orElseThrow().outcome());
        // The death message arrives a tick later, with Foo's pop still in range.
        Step step = feed(selfDied(), popOf("Foo"));
        assertEquals(Optional.empty(), step.finished());
        assertEquals(List.of(), step.live());
        assertFalse(tracker.fighting());
    }

    @Test
    void aOneShotFromIdleCountsFromYourLastTickAliveHoweverOld() {
        ticks.hostile("Foo", 4).health(20).totems(5).modules("auto-totem");
        feed();
        // Nobody near for a while: the adapter handed nothing over. Then one crystal kills you.
        ticks.skip(100).alive(false).health(0).totems(0).modules();
        FightRecord f = feed(crystalBy("Foo"), selfDied()).finished().orElseThrow();
        assertEquals(List.of(new DamageEvent(ticks.tick() - 1, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 20, 0, true)),
            f.damage());
        assertEquals(5, f.self().totemsStart());
        assertEquals(5, f.self().totemsEnd());
        assertEquals(List.of("auto-totem"), f.modulesAtStart());
    }

    @Test
    void dyingAfterAutoPvpEngagedIsLostEvenWithNoExchange() {
        ticks.autoPvp(CombatState.APPROACH, CombatPosture.CALM, "Foo");
        feed();
        idle(10);
        ticks.alive(false);
        FightRecord f = feed(unattributed(DamageKind.FALL), selfDied()).finished().orElseThrow();
        assertEquals(FightOutcome.LOST, f.outcome());
        assertEquals(List.of("Foo"), f.opponents().stream().map(Opponent::name).toList());
    }

    @Test
    void anOpponentsDeathWinsTheFightAfterSettlingNotATickSooner() {
        attackFoo();
        idle(10);
        long died = ticks.tick();
        assertEquals(List.of("Foo died"), render(feed(death("Foo")).live()));
        idle(FightTracker.SETTLE_TICKS - 1);
        FightRecord f = feed().finished().orElseThrow();
        assertEquals(FightOutcome.WON, f.outcome());
        assertEquals(millis(died), f.endedAt());
        assertTrue(f.opponents().getFirst().died());
    }

    @Test
    void anExchangeWithALivingOpponentKeepsAWonFightOpen() {
        attackFoo();
        ticks.hostile("Bar", 6);
        feed(meleeBy("Bar"));
        feed(death("Foo"));
        ticks.noHostile("Foo");
        idle(49);
        long last = ticks.tick();
        feed(crystalBy("Bar"));
        idle(FightTracker.SETTLE_TICKS - 1);
        FightRecord f = feed().finished().orElseThrow();
        assertEquals(FightOutcome.WON, f.outcome());
        assertEquals(millis(last), f.endedAt());
    }

    @Test
    void exchangesAboutTheDeadDoNotKeepItOpen() {
        attackFoo();
        feed(death("Foo"));
        // Foo's crystal hits you after Foo died, and you break crystals with nobody alive around.
        feed(crystalBy("Foo"));
        feed(broken());
        idle(FightTracker.SETTLE_TICKS - 3);
        assertEquals(FightOutcome.WON, feed().finished().orElseThrow().outcome());
    }

    @Test
    void aQuietFightEndsAfterFourHundredTicksNotThreeNinetyNineAtItsLastExchange() {
        long start = attackFoo();
        idle(59);
        long last = ticks.tick();
        feed(attack("Foo"));
        idle(FightTracker.QUIET_TICKS - 1);
        FightRecord f = feed().finished().orElseThrow();
        assertEquals(FightOutcome.ENDED, f.outcome());
        assertFalse(f.truncated());
        assertEquals(millis(last), f.endedAt());
        // Seconds 0 to 3 of the game clock: four begun seconds, one sample each.
        assertEquals(4, f.durationSeconds());
        // The quiet after the last exchange is not part of it: samples up to its second only.
        assertEquals(List.of(0, 1, 2, 3), f.samples().stream().map(Sample::second).toList());
        assertEquals(millis(start), f.startedAt());
    }

    @Test
    void aQuietFightDescribesOnlyItsSpanUpToTheLastExchange() {
        ticks.hostile("Foo", 5).totems(8);
        feed();
        long start = attackFoo();
        feed(placed());
        long last = ticks.tick();
        feed(crystalBy("Foo"));
        // The health for that hit lands two ticks later: still Foo's hit, still in the fight.
        feed();
        ticks.health(14);
        feed();
        // After the last exchange, from the next second on (samples and changes are kept by the second):
        // totems used, crowd around, a mob hit, crystals appearing, a new target.
        while (ticks.tick() < start + 20) feed();
        ticks.totems(5).offhandTotem(false).hostile("Bar", 3).hostile("Baz", 4)
            .autoPvp(CombatState.SURFACE, CombatPosture.CALM, "Qux");
        idle(10);
        ticks.health(10);
        feed(unattributed(DamageKind.MOB));
        feed(spawnedNear(), spawnedNear());
        while (ticks.tick() < last + FightTracker.QUIET_TICKS) feed();
        FightRecord f = feed().finished().orElseThrow();
        assertEquals(FightOutcome.ENDED, f.outcome());
        assertEquals(List.of(new DamageEvent(last, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 20, 14, false)), f.damage());
        assertEquals(6.0, f.self().damageTaken());
        assertEquals(8, f.self().totemsEnd());
        assertTrue(f.self().offhandTotemEnd());
        assertEquals(1, f.maxHostilesNear());
        assertEquals(1, f.self().crystalsPlaced());
        assertEquals(0, f.self().enemyCrystalsNear());
        assertEquals(List.of(new Opponent("Foo", 0, false, 1, 6.0, 0)), f.opponents());
        assertEquals(List.of(), f.phases());
    }

    @Test
    void damageAfterAnExchangeCountsOnceAnotherExchangeFollows() {
        attackFoo();
        ticks.health(15);
        feed(unattributed(DamageKind.MOB));
        feed(attack("Foo"));
        idle(FightTracker.QUIET_TICKS - 1);
        FightRecord f = feed().finished().orElseThrow();
        assertEquals(5.0, f.self().damageTaken());
        assertEquals(DamageKind.MOB, f.damage().getFirst().kind());
    }

    @Test
    void autoPvpSecondsNeverExceedTheDuration() {
        ticks.autoPvpWithoutPlan();
        long start = attackFoo();
        // Three seconds and five ticks: the fourth, begun second counts in both.
        while (ticks.tick() < start + 65) feed(placed());
        FightRecord f = abortNow();
        assertEquals(4, f.samples().size());
        assertEquals(4, f.durationSeconds());
        assertEquals(4, f.autoPvpSeconds());
        assertEquals(FightMode.AUTO_PVP, f.mode());
    }

    @Test
    void tenMinutesCutsTheFightShort() {
        long start = attackFoo();
        Optional<FightRecord> finished = Optional.empty();
        while (finished.isEmpty()) {
            finished = feed((ticks.tick() - start) % 300 == 0 ? attack("Foo") : placed()).finished();
            assertTrue(ticks.tick() - start <= FightTracker.MAX_TICKS);
        }
        FightRecord f = finished.get();
        assertEquals(start + FightTracker.MAX_TICKS, ticks.tick());
        assertEquals(FightOutcome.ENDED, f.outcome());
        assertTrue(f.truncated());
        assertEquals(600, f.samples().size());
        assertEquals(600, f.durationSeconds());
        assertFalse(tracker.fighting());
    }

    @Test
    void abortCutsItShort() {
        attackFoo();
        idle(9);
        long now = millis(ticks.tick());
        FightRecord f = tracker.abort(now).orElseThrow();
        assertEquals(FightOutcome.ABORTED, f.outcome());
        assertEquals(now, f.endedAt());
        assertFalse(tracker.fighting());
        assertEquals(Optional.empty(), tracker.abort(now));
    }

    // --- discard -----------------------------------------------------------------------------------

    @Test
    void walkingPastAStrangerWritesNothing() {
        ticks.hostile("Stranger", 3);
        for (int i = 0; i < 600; i++) {
            Step step = feed();
            assertEquals(Optional.empty(), step.finished());
            assertEquals(List.of(), step.live());
        }
        assertFalse(tracker.fighting());
    }

    @Test
    void autoPvpApproachingSomeoneWhoNeverFightsBackWritesNothing() {
        ticks.hostile("Foo", 12).autoPvp(CombatState.APPROACH, CombatPosture.CALM, "Foo");
        feed();
        idle(FightTracker.QUIET_TICKS - 1);
        Step step = feed();
        assertEquals(Optional.empty(), step.finished());
        assertEquals(List.of(), step.live());
        ticks.autoPvpOff();
        feed();
        assertFalse(tracker.fighting());
    }

    @Test
    void abortingAFightWithNoExchangeWritesNothing() {
        ticks.autoPvp(CombatState.APPROACH, CombatPosture.CALM, "Foo");
        feed();
        assertTrue(tracker.fighting());
        assertEquals(Optional.empty(), tracker.abort(millis(ticks.tick())));
    }

    // --- what it records ---------------------------------------------------------------------------

    @Test
    void theFirstHitCountsFromYourHealthOnTheTickBefore() {
        ticks.hostile("Foo", 4);
        feed();
        ticks.health(12);
        feed(crystalBy("Foo"));
        assertEquals(20.0, abortNow().damage().getFirst().before());
    }

    @Test
    void aStaleHealthFromLongBeforeIsNotTheBaseline() {
        ticks.hostile("Foo", 4).health(20);
        feed();
        // Hunger took you to 10 while nobody was near and the adapter handed nothing over.
        ticks.skip(100).health(10);
        feed(crystalBy("Foo"));
        ticks.health(4);
        feed();
        assertEquals(List.of(new DamageEvent(ticks.tick() - 2, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Foo", 10, 4, false)),
            abortNow().damage());
    }

    @Test
    void aSampleEveryTwentyTicksWithThatSecondsCrystals() {
        long start = attackFoo();
        feed(placed(), placed());
        idle(3);
        feed(spawnedNear());
        while (ticks.tick() < start + 25) feed();
        feed(broken());
        ticks.health(18);
        while (ticks.tick() < start + 45) feed();
        feed(spawnedNear(), spawnedNear(), spawnedNear(), spawnedNear());
        while (ticks.tick() < start + 60) feed();
        FightRecord f = abortNow();
        List<Sample> s = f.samples();
        assertEquals(List.of(0, 1, 2), s.stream().map(Sample::second).toList());
        assertEquals(List.of(2, 0, 0), s.stream().map(Sample::placed).toList());
        assertEquals(List.of(0, 1, 0), s.stream().map(Sample::broken).toList());
        assertEquals(List.of(1, 0, 4), s.stream().map(Sample::spawnedNear).toList());
        assertEquals(List.of(20.0, 18.0, 18.0), s.stream().map(Sample::health).toList());
        assertEquals(2, f.self().crystalsPlaced());
        assertEquals(1, f.self().crystalsBroken());
        assertEquals(3, f.self().enemyCrystalsNear());
        assertEquals(1, f.self().attacks());
    }

    @Test
    void enemyCrystalsNearIsNeverNegative() {
        attackFoo();
        feed(placed(), placed(), spawnedNear());
        assertEquals(0, abortNow().self().enemyCrystalsNear());
    }

    @Test
    void anEndMidSecondSamplesThatSecond() {
        attackFoo();
        idle(8);
        feed(placed());
        List<Sample> s = abortNow().samples();
        assertEquals(1, s.size());
        assertEquals(0, s.getFirst().second());
        assertEquals(1, s.getFirst().placed());
    }

    @Test
    void samplesMeasureTheHostilesAroundYou() {
        ticks.hostile("Bar", 7.9).hostile("Baz", 12).incoming(3.5).inHole(true).gliding(true).armor(3);
        attackFoo();
        idle(19);
        ticks.noHostile("Foo").noHostile("Bar").noHostile("Baz");
        idle(20);
        FightRecord f = abortNow();
        Sample first = f.samples().get(0);
        assertEquals(5.0, first.nearestHostile());
        assertEquals(2, first.hostilesNear());
        assertEquals(3.5, first.incoming());
        assertTrue(first.inHole());
        assertTrue(first.gliding());
        assertEquals(3, first.armor());
        assertNull(f.samples().get(1).nearestHostile());
        assertEquals(0, f.samples().get(1).hostilesNear());
        assertEquals(2, f.maxHostilesNear());
        // Nearby players are not opponents.
        assertEquals(List.of("Foo"), f.opponents().stream().map(Opponent::name).toList());
    }

    @Test
    void moduleChangesAreRecordedOnlyWhenSomethingChanges() {
        ticks.modules("crystal-aura", "auto-totem");
        long start = attackFoo();
        while (ticks.tick() < start + 25) feed();
        ticks.modules("crystal-aura", "auto-totem", "surround");
        while (ticks.tick() < start + 45) feed();
        ticks.modules("auto-totem", "surround");
        idle(10);
        FightRecord f = abortNow();
        assertEquals(List.of("auto-totem", "crystal-aura"), f.modulesAtStart());
        assertEquals(List.of(new ModuleChange(1, "surround", true), new ModuleChange(2, "crystal-aura", false)),
            f.moduleChanges());
    }

    @Test
    void phasesAreRecordedOnlyWhenPhasePostureOrTargetChanges() {
        ticks.autoPvp(CombatState.SURFACE, CombatPosture.CALM, "Foo");
        long start = attackFoo();
        while (ticks.tick() < start + 30) feed();
        ticks.autoPvp(CombatState.SURFACE, CombatPosture.THREATENED, "Foo");
        idle(5);
        // No plan for a while, then the same phase again: nothing new.
        ticks.autoPvpWithoutPlan();
        idle(5);
        ticks.autoPvp(CombatState.SURFACE, CombatPosture.THREATENED, "Foo");
        idle(5);
        ticks.hostile("Bar", 6).autoPvp(CombatState.SURFACE, CombatPosture.THREATENED, "Bar");
        while (ticks.tick() < start + 60) feed();
        FightRecord f = abortNow();
        assertEquals(List.of(
            new PhaseChange(0, CombatState.SURFACE, CombatPosture.CALM, "Foo"),
            new PhaseChange(1, CombatState.SURFACE, CombatPosture.THREATENED, "Foo"),
            new PhaseChange(2, CombatState.SURFACE, CombatPosture.THREATENED, "Bar")), f.phases());
        // auto-pvp's target is an opponent.
        assertEquals(List.of("Foo", "Bar"), f.opponents().stream().map(Opponent::name).toList());
    }

    @Test
    void pastThreeHundredDamageEventsOnlyTheTotalsGrow() {
        ticks.health(1000).hostile("Foo", 3);
        feed();
        for (int i = 1; i <= 350; i++) {
            ticks.health(1000 - i);
            feed(crystalBy("Foo"));
        }
        FightRecord f = abortNow();
        assertEquals(FightTracker.MAX_DAMAGE_EVENTS, f.damage().size());
        assertEquals(50, f.damageEventsDropped());
        assertEquals(350.0, f.self().damageTaken());
        assertEquals(new Opponent("Foo", 0, false, 350, 350.0, 0), f.opponents().getFirst());
    }

    @Test
    void changesAreCappedAndTheLastPhaseIsKept() {
        attackFoo();
        for (int i = 0; i < 250; i++) {
            if (i % 2 == 0) {
                ticks.modules("surround").autoPvp(CombatState.SURFACE, CombatPosture.THREATENED, "Foo");
            } else {
                ticks.modules().autoPvp(CombatState.SURFACE, CombatPosture.CALM, "Foo");
            }
            feed(attack("Foo"));
        }
        ticks.autoPvp(CombatState.CHASE, CombatPosture.CALM, "Foo");
        feed();
        FightRecord f = abortNow();
        assertEquals(FightTracker.MAX_CHANGES, f.moduleChanges().size());
        assertEquals(FightTracker.MAX_CHANGES, f.phases().size());
        assertEquals(CombatState.CHASE, f.phases().getLast().state());
    }

    @Test
    void opponentsAreThoseYouFoughtInTheOrderTheyCame() {
        ticks.hostile("Foo", 4).hostile("Bar", 5).hostile("Zed", 9);
        feed();
        ticks.health(14);
        feed(crystalBy("Foo"));
        feed(attack("Bar"), damaged("Qux", true), damaged("Qux", true), damaged("Nobody", false));
        feed(popOf("Zed"), popOf("Zed"), popOf("FarAway"));
        feed(death("Bar"));
        FightRecord f = abortNow();
        assertEquals(List.of(
            new Opponent("Foo", 0, false, 1, 6.0, 0),
            new Opponent("Bar", 0, true, 0, 0.0, 0),
            new Opponent("Qux", 0, false, 0, 0.0, 2),
            new Opponent("Zed", 2, false, 0, 0.0, 0)), f.opponents());
    }

    @Test
    void anAllysHitIsRecordedButTheAllyIsNoOpponent() {
        attackFoo();
        ticks.health(15);
        feed(Ticks.allyCrystal("Ally"));
        FightRecord f = abortNow();
        assertEquals(List.of(new DamageEvent(ticks.tick() - 1, DamageKind.CRYSTAL, AttackerKind.PLAYER, "Ally", 20, 15, false)),
            f.damage());
        assertEquals(List.of("Foo"), f.opponents().stream().map(Opponent::name).toList());
    }

    /** A thirty-second fight with auto-pvp on for {@code autoSeconds} of them. */
    private FightRecord thirtySecondsWithAutoPvp(int autoSeconds) {
        long start = attackFoo();
        while (ticks.tick() < start + 600) {
            if (ticks.tick() - start == autoSeconds * 20L) ticks.autoPvpOff();
            else if (ticks.tick() - start < autoSeconds * 20L) ticks.autoPvpWithoutPlan();
            feed((ticks.tick() - start) % 100 == 0 ? attack("Foo") : placed());
        }
        return abortNow();
    }

    @Test
    void autoPvpForNinetyPercentOfTheSecondsIsAnAutoPvpFight() {
        FightRecord f = thirtySecondsWithAutoPvp(27);
        assertEquals(30, f.samples().size());
        assertEquals(27, f.autoPvpSeconds());
        assertEquals(FightMode.AUTO_PVP, f.mode());
    }

    @Test
    void autoPvpForOneSecondLessIsMixed() {
        FightRecord f = thirtySecondsWithAutoPvp(26);
        assertEquals(26, f.autoPvpSeconds());
        assertEquals(FightMode.MIXED, f.mode());
    }

    @Test
    void withoutAutoPvpItIsManual() {
        FightRecord f = thirtySecondsWithAutoPvp(0);
        assertEquals(0, f.autoPvpSeconds());
        assertEquals(FightMode.MANUAL, f.mode());
    }

    @Test
    void theRecordHasTheSchemaAndTheAddonVersion() {
        attackFoo();
        FightRecord f = abortNow();
        assertEquals(FightRecord.SCHEMA, f.schema());
        assertEquals("0.5.0", f.addonVersion());
    }

    // --- live lines --------------------------------------------------------------------------------

    @Test
    void popsAreToldAsTheyHappen() {
        ticks.hostile("Foo", 4).totems(8);
        feed();
        ticks.totems(7);
        assertEquals(List.of("Fight started · Foo", "You popped · 1 this fight · 7 totems left"),
            render(feed(crystalBy("Foo"), ownPop()).live()));
        assertEquals(List.of("Foo popped · 1 this fight"), render(feed(popOf("Foo")).live()));
        assertEquals(List.of("Foo popped · 2 this fight"), render(feed(popOf("Foo")).live()));
    }

    @Test
    void aHitOfEightIsBigAndOfSevenPointNineIsNot() {
        ticks.hostile("Foo", 4);
        long start = attackFoo();
        ticks.health(12);
        assertEquals(List.of("Big hit · 8.0 damage · crystal · by Foo"), render(feed(crystalBy("Foo")).live()));
        ticks.health(4.1);
        assertEquals(List.of(), render(feed(crystalBy("Foo")).live()));
        ticks.health(20);
        feed();
        ticks.health(10);
        assertEquals(List.of("Big hit · 10.0 damage · crystal · yourself"), render(feed(ownCrystal()).live()));
        assertTrue(ticks.tick() - start < 10);
    }

    @Test
    void runningOutOfCrystalsOrTotemsIsTold() {
        attackFoo();
        ticks.crystals(0);
        assertEquals(List.of("Out of crystals"), render(feed().live()));
        assertEquals(List.of(), feed().live());
        ticks.totems(0);
        assertEquals(List.of("Out of totems"), render(feed().live()));
    }

    @Test
    void nothingIsToldBeforeTheFirstExchange() {
        ticks.autoPvp(CombatState.APPROACH, CombatPosture.CALM, "Foo");
        feed();
        ticks.crystals(0);
        assertEquals(List.of(), feed().live());
    }

    // --- state -------------------------------------------------------------------------------------

    @Test
    void secondsCountFromTheStart() {
        assertEquals(0, tracker.seconds());
        attackFoo();
        idle(45);
        assertEquals(2, tracker.seconds());
    }

    @Test
    void resetForgetsTheFight() {
        attackFoo();
        tracker.reset();
        assertFalse(tracker.fighting());
        assertEquals(Optional.empty(), tracker.abort(millis(ticks.tick())));
    }
}
