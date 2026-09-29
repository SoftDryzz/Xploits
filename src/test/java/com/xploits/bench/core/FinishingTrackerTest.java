package com.xploits.bench.core;

import com.xploits.bench.core.FinishingTracker.Blow;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.DamageKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B0b: the finishing blows of crystal-aura++ told apart from its ordinary hits, from what the client
 * sees each tick (the marked crystal ids, which are still in the world, our health and totems).
 */
class FinishingTrackerTest {
    private static final Set<Integer> NONE = Set.of();

    private static void tick(FinishingTracker t, Set<Integer> marked, Set<Integer> present, double health, int totems, long clock) {
        t.observe(marked, present, health, totems, clock);
    }

    @Test
    void noMarkedCrystalIsNoBlow() {
        FinishingTracker t = new FinishingTracker();
        for (int i = 0; i < 5; i++) tick(t, NONE, NONE, 20, 8, i);
        assertEquals(0, t.count());
        assertTrue(t.minHealthAfter().isEmpty());
        assertTrue(t.minTotems().isEmpty());
    }

    @Test
    void aMarkedCrystalStillStandingIsNoBlowYet() {
        FinishingTracker t = new FinishingTracker();
        tick(t, NONE, NONE, 20, 8, 1);
        tick(t, Set.of(7), Set.of(7), 20, 8, 2);
        tick(t, Set.of(7), Set.of(7), 20, 8, 3);
        assertEquals(0, t.count());
    }

    @Test
    void aMarkedCrystalThatLeftTheWorldIsABlowWithTheTotemsAndHealthOfTheTickBefore() {
        FinishingTracker t = new FinishingTracker();
        tick(t, NONE, NONE, 20, 8, 1);
        tick(t, Set.of(7), Set.of(7), 6, 3, 2);
        tick(t, Set.of(7), NONE, 1.5, 2, 3);
        tick(t, Set.of(7), NONE, 1.5, 2, 4);
        tick(t, Set.of(7), NONE, 9, 2, 5);
        assertEquals(1, t.count());
        Blow blow = t.blows().getFirst();
        assertEquals(6, blow.healthBefore());
        assertEquals(3, blow.totems());
        assertEquals(3, blow.tick());
        assertEquals(OptionalDouble.of(1.5), t.minHealthAfter());
        assertEquals(OptionalInt.of(3), t.minTotems());
    }

    @Test
    void aBlowThatKilledUsReadsZeroAfter() {
        FinishingTracker t = new FinishingTracker();
        tick(t, NONE, NONE, 6, 1, 1);
        tick(t, Set.of(4), Set.of(4), 6, 1, 2);
        tick(t, Set.of(4), NONE, 0, 1, 3);
        assertEquals(OptionalDouble.of(0), t.minHealthAfter());
        assertEquals(OptionalInt.of(1), t.minTotems());
    }

    @Test
    void theHealthAfterIsTheLowestOverTheWindowSinceTheDamageArrivesATickLate() {
        FinishingTracker t = new FinishingTracker();
        tick(t, NONE, NONE, 8, 2, 1);
        tick(t, Set.of(4), Set.of(4), 8, 2, 2);
        tick(t, Set.of(4), NONE, 8, 2, 3);   // gone, damage not on the client yet
        tick(t, Set.of(4), NONE, 2.5, 2, 4); // the health update lands
        tick(t, Set.of(4), NONE, 2.5, 2, 5);
        assertEquals(OptionalDouble.of(2.5), t.minHealthAfter());
    }

    @Test
    void aCrystalAlreadyGoneTheFirstTimeItIsSeenStillCounts() {
        FinishingTracker t = new FinishingTracker();
        tick(t, NONE, NONE, 8, 4, 1);
        tick(t, Set.of(9), NONE, 3, 4, 2);
        assertEquals(1, t.count());
        assertEquals(8, t.blows().getFirst().healthBefore());
    }

    @Test
    void theSameIdIsCountedOnceHoweverLongTheBrainRemembersIt() {
        FinishingTracker t = new FinishingTracker();
        tick(t, Set.of(4), Set.of(4), 8, 4, 1);
        for (int i = 2; i < 12; i++) tick(t, Set.of(4), NONE, 8, 4, i);
        assertEquals(1, t.count());
    }

    @Test
    void theFewestTotemsIsTheMinimumOverAllBlows() {
        FinishingTracker t = new FinishingTracker();
        tick(t, Set.of(1), Set.of(1), 8, 5, 1);
        tick(t, Set.of(1), NONE, 8, 4, 2);
        tick(t, Set.of(1, 2), Set.of(2), 8, 4, 3);
        tick(t, Set.of(1, 2), NONE, 8, 3, 4);
        assertEquals(2, t.count());
        assertEquals(OptionalInt.of(4), t.minTotems());
    }

    // --- Telling the recorder's events apart -----------------------------------------------------------

    private static DamageEvent own(long tick, double before, double after) {
        return new DamageEvent(tick, DamageKind.CRYSTAL, AttackerKind.SELF, null, before, after, false);
    }

    private static Blow blow(long tick, double before) {
        return new Blow(tick, before, List.of(), 1, 3);
    }

    @Test
    void anOwnEventAtTheBlowsTickWithItsHealthBeforeIsAFinishingHit() {
        assertEquals(Set.of(100L), FinishingTracker.hitTicks(List.of(own(100, 6, 1)), List.of(blow(101, 6))));
    }

    @Test
    void anOrdinaryOwnHitAtTheSameHealthButAnotherTimeIsNotOne() {
        List<DamageEvent> damage = List.of(own(40, 20, 12), own(100, 20, 1));
        assertEquals(Set.of(100L), FinishingTracker.hitTicks(damage, List.of(blow(101, 20))));
    }

    @Test
    void anEventFromAnotherSourceIsNeverOne() {
        DamageEvent theirs = new DamageEvent(100, DamageKind.CRYSTAL, AttackerKind.PLAYER, "x", 6, 1, false);
        assertTrue(FinishingTracker.hitTicks(List.of(theirs), List.of(blow(100, 6))).isEmpty());
    }

    @Test
    void anOwnEventNearTheBlowWithAnotherHealthBeforeIsNotOne() {
        assertTrue(FinishingTracker.hitTicks(List.of(own(100, 12, 6)), List.of(blow(100, 6))).isEmpty());
    }

    @Test
    void aBlowSeenLateStillMatchesThroughTheEarlierSamples() {
        Blow late = new Blow(103, 4, List.of(6.0, 6.0), 1, 3);
        assertEquals(Set.of(101L), FinishingTracker.hitTicks(List.of(own(101, 6, 1)), List.of(late)));
    }

    @Test
    void withoutBlowsNothingIsExcluded() {
        assertTrue(FinishingTracker.hitTicks(List.of(own(100, 6, 1)), List.of()).isEmpty());
    }
}
