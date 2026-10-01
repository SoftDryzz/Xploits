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

    // 0.8.0: a crystal the opponent's script spawned is never ours

    @Test
    void aClaimedCrystalTheOpponentsScriptSpawnedIsNotOurs() {
        // The release bench's F3 failure, by its values: the recorder claimed the opponent's crystal 209 (a leftover
        // claim of our placement burst on the same cell) and the opponent set it off: 7.174 -> 2.785, below 3.5.
        Set<Integer> ids = OwnHits.ownIds(Set.of(950), Set.of(950, 209), Set.of(209));
        assertEquals(Set.of(950), ids);
        List<DamageEvent> damage = List.of(bySelf(5, 20, 14.458), bySparring(100, 7.174, 2.785));
        List<HitSource> sources = List.of(new HitSource(5, 950), new HitSource(100, 209));
        Set<Integer> ours = OwnHits.indexes(damage, sources, ids);
        assertEquals(Set.of(0), ours);
        assertEquals(OptionalDouble.of(14.458), MinHealthAfterOwnHit.of(damage, Set.of(), ours));
        // Before: every claimed id counted, and the opponent's hit set the minimum.
        assertEquals(OptionalDouble.of(2.785),
            MinHealthAfterOwnHit.of(damage, Set.of(), OwnHits.indexes(damage, sources, Set.of(950, 209))));
    }

    @Test
    void anOpponentsCrystalTheBrainTookForOursIsNotOursEither() {
        // The opponent's crystal landed on a spot where our placement was pending, so the brain listed it as ours.
        assertEquals(Set.of(951), OwnHits.ownIds(Set.of(951, 703), Set.of(), Set.of(703)));
    }

    @Test
    void ourCrystalSetOffByTheOpponentsAutobreakStillCounts() {
        // Not spawned by any script: ours, whoever set it off (task C2, I2).
        Set<Integer> ids = OwnHits.ownIds(Set.of(950), Set.of(950), Set.of(209, 211));
        List<DamageEvent> damage = List.of(bySparring(10, 20, 4));
        assertEquals(Set.of(0), OwnHits.indexes(damage, List.of(new HitSource(10, 950)), ids));
    }

    @Test
    void theRecordersOwnAttributionIsLeftAsItIs() {
        // Our own attack on a crystal the opponent spawned: the recorder gives it to us, and it stays ours.
        Set<Integer> ids = OwnHits.ownIds(Set.of(), Set.of(209), Set.of(209));
        assertTrue(ids.isEmpty());
        List<DamageEvent> damage = List.of(bySelf(10, 20, 15));
        assertEquals(Set.of(0), OwnHits.indexes(damage, List.of(new HitSource(10, 209)), ids));
    }

    @Test
    void theUnionOfBothSourcesIsKept() {
        assertEquals(Set.of(950, 951, 952), OwnHits.ownIds(Set.of(950, 951), Set.of(951, 952), Set.of()));
    }
}
