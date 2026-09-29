package com.xploits.bench.core;

import com.xploits.bench.core.FinishingTracker.Blow;
import com.xploits.bench.core.FinishingTracker.Resolution;
import com.xploits.pvp.crystal.core.FinishKind;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.DamageKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B0b (fix round 1): finishing blows told apart from ordinary hits: candidates from the client's view,
 * blows only with a recorder event, one event per blow, excluded by event, kind from the module.
 */
class FinishingTrackerTest {
    private static final Map<Integer, FinishKind> NONE = Map.of();
    private static final Set<Integer> GONE = Set.of();

    private static Map<Integer, FinishKind> kill(int id) {
        return Map.of(id, FinishKind.KILL);
    }

    private static Map<Integer, FinishKind> pop(int id) {
        return Map.of(id, FinishKind.POP);
    }

    private static DamageEvent own(long tick, double before, double after) {
        return new DamageEvent(tick, DamageKind.CRYSTAL, AttackerKind.SELF, null, before, after, false);
    }

    /** Crystal 7 stands, then explodes on clock tick 3; health {@code h[i]} and totems {@code t[i]} from tick 1. */
    private static FinishingTracker blowOf(Map<Integer, FinishKind> kind, double[] h, int[] t) {
        FinishingTracker tr = new FinishingTracker();
        tr.observe(NONE, GONE, h[0], t[0], 1);
        tr.observe(kind, Set.of(7), h[1], t[1], 2);
        for (int i = 2; i < h.length; i++) tr.observe(kind, GONE, h[i], t[i], 1 + i);
        return tr;
    }

    // --- Candidates -------------------------------------------------------------------------------------

    @Test
    void nothingMarkedIsNoBlow() {
        FinishingTracker t = new FinishingTracker();
        for (int i = 0; i < 5; i++) t.observe(NONE, GONE, 20, 8, i);
        assertEquals(0, t.resolve(List.of(own(2, 20, 10))).count());
    }

    @Test
    void aMarkedCrystalStillStandingIsNoBlow() {
        FinishingTracker t = new FinishingTracker();
        t.observe(NONE, GONE, 20, 8, 1);
        t.observe(kill(7), Set.of(7), 20, 8, 2);
        t.observe(kill(7), Set.of(7), 20, 8, 3);
        assertEquals(0, t.resolve(List.of(own(3, 20, 10))).count());
    }

    @Test
    void aCrystalThatLeftTheWorldWithAnEventOfOursIsABlowWithTheTotemsAndHealthOfTheTickBefore() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 6, 1.5, 1.5, 9}, new int[] {4, 3, 2, 2, 2});
        Resolution r = t.resolve(List.of(own(3, 6, 1.5)));
        assertEquals(1, r.count());
        Blow b = r.blows().getFirst();
        assertEquals(6, b.healthBefore());
        assertEquals(3, b.totems());
        assertEquals(3, b.tick());
        assertEquals(OptionalDouble.of(1.5), r.minHealthAfter());
        assertEquals(OptionalInt.of(3), r.minTotems());
        assertEquals(Set.of(0), r.excluded());
    }

    @Test
    void aCrystalTheOpponentBrokeThatDealtUsNothingIsNoBlow() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 6, 6, 6, 6}, new int[] {4, 4, 4, 4, 4});
        // No event of ours: not a blow, and nothing to exclude.
        Resolution r = t.resolve(List.of());
        assertEquals(0, r.count());
        assertTrue(r.excluded().isEmpty());
        // An event by someone else is not ours either.
        DamageEvent theirs = new DamageEvent(3, DamageKind.CRYSTAL, AttackerKind.PLAYER, "x", 6, 2, false);
        assertEquals(0, t.resolve(List.of(theirs)).count());
    }

    @Test
    void aBlowThatKilledUsReadsZeroAfter() {
        FinishingTracker t = blowOf(kill(7), new double[] {6, 6, 0, 0}, new int[] {1, 1, 1, 1});
        assertEquals(OptionalDouble.of(0), t.resolve(List.of(own(3, 6, 0))).minHealthAfter());
    }

    @Test
    void theHealthAfterIsTheLowestOverTheWindowWhenTheDamageArrivesATickLate() {
        FinishingTracker t = blowOf(kill(7), new double[] {8, 8, 8, 2.5, 2.5}, new int[] {2, 2, 2, 2, 2});
        assertEquals(OptionalDouble.of(2.5), t.resolve(List.of(own(3, 8, 2.5))).minHealthAfter());
    }

    @Test
    void aCrystalAlreadyGoneTheFirstTimeItIsSeenStillCounts() {
        FinishingTracker t = new FinishingTracker();
        t.observe(NONE, GONE, 8, 4, 1);
        t.observe(kill(9), GONE, 3, 4, 2);
        assertEquals(1, t.resolve(List.of(own(2, 8, 3))).count());
    }

    @Test
    void theSameIdIsCountedOnceHoweverLongTheBrainRemembersIt() {
        FinishingTracker t = new FinishingTracker();
        t.observe(kill(4), Set.of(4), 8, 4, 1);
        for (int i = 2; i < 12; i++) t.observe(kill(4), GONE, 8, 4, i);
        assertEquals(1, t.resolve(List.of(own(2, 8, 4), own(3, 8, 4))).count());
    }

    // --- One event per blow, excluded by event ---------------------------------------------------------

    @Test
    void anOrdinaryHitTwoTicksBeforeAtTheSameHealthIsNotTakenForTheBlow() {
        // Ordinary hit at tick 1 (20 -> 12), then health is back at 20 (a gapple) and the blow comes at tick 3.
        FinishingTracker t = blowOf(kill(7), new double[] {20, 20, 6, 6, 6}, new int[] {3, 3, 3, 3, 3});
        List<DamageEvent> damage = List.of(own(1, 20, 12), own(3, 20, 6));
        Resolution r = t.resolve(damage);
        assertEquals(1, r.count());
        // The nearest tick wins, so the blow takes event 1 (tick 3), never the ordinary one at tick 1.
        assertEquals(Set.of(1), r.excluded());
    }

    @Test
    void anOrdinaryHitJustBeforeThatChangedTheHealthIsNeverTakenEvenWithinTolerance() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 12, 6, 6, 6}, new int[] {3, 3, 3, 3, 3});
        // Ordinary 20 -> 12 at tick 2 (before 20 != health before the blow, 12), blow 12 -> 6 at tick 3.
        Resolution r = t.resolve(List.of(own(2, 20, 12), own(3, 12, 6)));
        assertEquals(Set.of(1), r.excluded());
    }

    @Test
    void anEventWithAnotherHealthBeforeIsNeverTheBlowsEvenAloneWithinTolerance() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 12, 6, 6, 6}, new int[] {3, 3, 3, 3, 3});
        assertEquals(0, t.resolve(List.of(own(2, 20, 12))).count());
    }

    @Test
    void whenTwoEventsAreEquallyNearTheEarlierOneIsTaken() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 12, 6, 6, 6}, new int[] {3, 3, 3, 3, 3});
        assertEquals(Set.of(0), t.resolve(List.of(own(2, 12, 6), own(4, 12, 6))).excluded());
    }

    @Test
    void anOrdinaryHitOnTheSameTickIsNeverHiddenFromTheReserveRules() {
        // Two crystals exploding together: an ordinary one and the finishing one: they cannot be told apart,
        // so the blow counts but no event is excluded (the ordinary hit still counts for F3).
        FinishingTracker t = blowOf(kill(7), new double[] {20, 14, 8, 8, 8}, new int[] {3, 3, 3, 3, 3});
        List<DamageEvent> damage = List.of(own(3, 14, 11), own(3, 11, 8));
        Resolution r = t.resolve(damage);
        assertEquals(1, r.count());
        assertTrue(r.excluded().isEmpty());
        assertEquals(1, r.ambiguous());
        assertEquals(OptionalDouble.of(8), MinHealthAfterOwnHit.of(damage, r.excluded()));
    }

    @Test
    void oneEventIsTakenByOneBlowOnly() {
        // Two crystals marked, both gone on the same tick, only one event: one blow, not two.
        FinishingTracker t = new FinishingTracker();
        t.observe(NONE, GONE, 20, 4, 1);
        t.observe(Map.of(1, FinishKind.KILL, 2, FinishKind.KILL), Set.of(1, 2), 20, 4, 2);
        t.observe(Map.of(1, FinishKind.KILL, 2, FinishKind.KILL), GONE, 9, 4, 3);
        t.observe(Map.of(1, FinishKind.KILL, 2, FinishKind.KILL), GONE, 9, 4, 4);
        t.observe(Map.of(1, FinishKind.KILL, 2, FinishKind.KILL), GONE, 9, 4, 5);
        assertEquals(1, t.resolve(List.of(own(3, 20, 9))).count());
    }

    @Test
    void theOrdinaryMinimumIgnoresOnlyTheFinishingEvent() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 9, 2, 2, 2}, new int[] {3, 3, 3, 3, 3});
        List<DamageEvent> damage = List.of(own(1, 20, 9), own(3, 9, 2));
        Resolution r = t.resolve(damage);
        assertEquals(OptionalDouble.of(9), MinHealthAfterOwnHit.of(damage, r.excluded()));
    }

    // --- Health outside the blow's window ----------------------------------------------------------------

    @Test
    void theLowestHealthOutsideLeavesOutOnlyTheBlowsWindow() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 20, 2, 2, 2, 15}, new int[] {3, 3, 3, 3, 3, 3});
        List<Blow> blows = t.resolve(List.of(own(3, 20, 2))).blows();
        assertEquals(OptionalDouble.of(15), t.minHealthOutside(blows));
        // Without a confirmed blow nothing is left out.
        assertEquals(OptionalDouble.of(2), t.minHealthOutside(List.of()));
    }

    @Test
    void resettingForgetsTheBlowsSoFar() {
        FinishingTracker t = blowOf(kill(7), new double[] {20, 20, 2, 2, 2}, new int[] {3, 3, 3, 3, 3});
        t.reset();
        assertEquals(0, t.resolve(List.of(own(3, 20, 2))).count());
    }

    // --- Kind, from the module ----------------------------------------------------------------------------

    @Test
    void theKindIsWhatTheModuleMarked() {
        Resolution killR = blowOf(kill(7), new double[] {6, 6, 5, 5}, new int[] {3, 3, 3, 3}).resolve(List.of(own(3, 6, 5)));
        assertEquals(1, killR.kills());
        assertEquals(0, killR.popBlows());
        Resolution popR = blowOf(pop(7), new double[] {6, 6, 5, 5}, new int[] {3, 3, 3, 3}).resolve(List.of(own(3, 6, 5)));
        assertEquals(0, popR.kills());
        assertEquals(1, popR.popBlows());
    }

    @Test
    void aCrystalEverSeenKillStaysKill() {
        FinishingTracker t = new FinishingTracker();
        t.observe(NONE, GONE, 6, 3, 1);
        t.observe(kill(7), Set.of(7), 6, 3, 2);
        t.observe(pop(7), Set.of(7), 6, 3, 3);
        t.observe(pop(7), GONE, 5, 3, 4);
        t.observe(pop(7), GONE, 5, 3, 5);
        t.observe(pop(7), GONE, 5, 3, 6);
        assertEquals(1, t.resolve(List.of(own(4, 6, 5))).kills());
    }

    @Test
    void aPopMarkedBlowThatPoppedUsIsAViolationWhateverTheTargetHolds() {
        Resolution r = blowOf(pop(7), new double[] {6, 6, 9, 9}, new int[] {3, 3, 2, 2}).resolve(List.of(own(3, 6, 0)));
        assertEquals(1, r.pops());
        assertEquals(1, r.popGradeViolations());
    }

    @Test
    void aPopMarkedBlowBelowTwoIsAViolationAtTwoItIsNot() {
        assertEquals(1, blowOf(pop(7), new double[] {6, 6, 1.5, 1.5}, new int[] {3, 3, 3, 3})
            .resolve(List.of(own(3, 6, 1.5))).popGradeViolations());
        assertEquals(0, blowOf(pop(7), new double[] {6, 6, 2.0, 2.0}, new int[] {3, 3, 3, 3})
            .resolve(List.of(own(3, 6, 2))).popGradeViolations());
    }

    @Test
    void aKillMarkedBlowMayPopUsWithoutBeingAViolation() {
        Resolution r = blowOf(kill(7), new double[] {6, 6, 9, 9}, new int[] {3, 3, 2, 2}).resolve(List.of(own(3, 6, 0)));
        assertEquals(1, r.pops());
        assertEquals(0, r.popGradeViolations());
    }
}
