package com.xploits.bench.core;

import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.DamageKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import com.xploits.pvp.recorder.core.HitSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task C2 (I2): a hit on us is OURS for the reserve rules when its direct source was a crystal we placed, even
 * when the sparring's autobreak set it off and the recorder gave it to the sparring.
 */
class OwnHitsTest {
    private static final Set<Integer> MINE = Set.of(950, 951);

    private static DamageEvent bySelf(long tick, double before, double after) {
        return new DamageEvent(tick, DamageKind.CRYSTAL, AttackerKind.SELF, null, before, after, false);
    }

    private static DamageEvent bySparring(long tick, double before, double after) {
        return new DamageEvent(tick, DamageKind.CRYSTAL, AttackerKind.PLAYER, "sparring", before, after, false);
    }

    private static DamageEvent unseen(long tick, double before, double after) {
        return new DamageEvent(tick, DamageKind.UNSEEN, AttackerKind.NONE, null, before, after, false);
    }

    @Test
    void anAutobrokenOwnCrystalsHitCountsAndReachesTheReserveMinimum() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 950)), MINE);
        assertEquals(Set.of(0), ours);
        assertEquals(OptionalDouble.of(4), MinHealthAfterOwnHit.of(damage, Set.of(), ours));
        // Before the fix the sparring got it: F3 saw nothing.
        assertTrue(MinHealthAfterOwnHit.of(damage).isEmpty());
    }

    @Test
    void aForeignCrystalsHitDoesNotCount() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 999)), MINE);
        assertTrue(ours.isEmpty());
        assertTrue(MinHealthAfterOwnHit.of(damage, Set.of(), ours).isEmpty());
    }

    @Test
    void theRecordersOwnAttributionStillCounts() {
        List<DamageEvent> damage = List.of(bySelf(10, 20, 12));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 999)), MINE);
        assertEquals(Set.of(0), ours);
    }

    @Test
    void aFinishingHitIsStillLeftOutByEventEvenWhenAutobroken() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 9), bySparring(50, 6, 1));
        List<HitSource> sources = List.of(new HitSource(10, 950), new HitSource(50, 951));
        Set<Integer> ours = OwnHits.indexes(damage, sources, MINE);
        assertEquals(Set.of(0, 1), ours);
        assertEquals(OptionalDouble.of(9), MinHealthAfterOwnHit.of(damage, Set.of(1), ours));
        assertEquals(OptionalDouble.of(1), MinHealthAfterOwnHit.of(damage, Set.of(), ours));
    }

    @Test
    void twoHitsOnOneTickAreMatchedInOrder() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 14), bySparring(10, 14, 8));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 999), new HitSource(10, 950)), MINE);
        assertEquals(Set.of(1), ours);
    }

    @Test
    void anUnseenEventTakesNoPacketOfItsOwn() {
        List<DamageEvent> damage = List.of(unseen(10, 20, 18), bySparring(10, 18, 8));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 950)), MINE);
        assertEquals(Set.of(1), ours);
    }

    @Test
    void whenTheCountsDisagreeOnATickAHitThereIsOursIfAnySourceOfThatTickWasOursNeverHidden() {
        // Two packets but one event (the other produced no drop): which one it was cannot be told.
        List<DamageEvent> damage = List.of(bySparring(10, 20, 8));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 999), new HitSource(10, 950)), MINE);
        assertEquals(Set.of(0), ours);
    }

    @Test
    void noSourceLoggedForATickLeavesTheRecordersAttribution() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 8), bySelf(30, 20, 15));
        assertEquals(Set.of(1), OwnHits.indexes(damage, List.of(), MINE));
    }

    // --- by the spot: a crystal broken in the tick it appeared has an id nobody saw ----------------------------

    private static final Set<Long> PLACED = Set.of(1001L, 1002L);

    @Test
    void anUnknownIdOnAPlacedCellIsOurs() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 777, 1001L)), MINE, PLACED);
        assertEquals(Set.of(0), ours);
        assertEquals(OptionalDouble.of(4), MinHealthAfterOwnHit.of(damage, Set.of(), ours));
    }

    @Test
    void anUnknownIdOnAnUnplacedCellIsNotOurs() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        assertTrue(OwnHits.indexes(damage, List.of(new HitSource(10, 777, 2000L)), MINE, PLACED).isEmpty());
    }

    @Test
    void aPacketWithNoCellIsNeverMatchedBySpot() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        assertTrue(OwnHits.indexes(damage, List.of(new HitSource(10, 777)), MINE, PLACED).isEmpty());
    }

    @Test
    void theSameAttributionWhateverModuleSentThePlacements() {
        // Meteor's runs know no crystal id, only the cells the placement packets were sent for.
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(10, 777, 1002L)), Set.of(), PLACED);
        assertEquals(Set.of(0), ours);
    }

    @Test
    void theSpotAlsoDecidesWhenTheCountsOnATickDisagree() {
        List<DamageEvent> damage = List.of(bySparring(10, 20, 8));
        List<HitSource> packets = List.of(new HitSource(10, 999, 2000L), new HitSource(10, 777, 1001L));
        assertEquals(Set.of(0), OwnHits.indexes(damage, packets, Set.of(), PLACED));
    }
}
