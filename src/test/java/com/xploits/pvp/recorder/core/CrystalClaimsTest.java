package com.xploits.pvp.recorder.core;

import com.xploits.bench.core.OwnHits;
import com.xploits.pvp.recorder.core.CombatEvent.AttackerKind;
import com.xploits.pvp.recorder.core.FightRecord.DamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round 3 of C2: a crystal is ours only when it lands on a spot we just placed on, one placement claiming at most
 * one crystal. The opponent's crystals on cells we used earlier are not ours. Same for Meteor's placements: the
 * recorder sees the packets, whichever module sent them.
 */
class CrystalClaimsTest {
    private static final long CELL = 4711L;
    private static final long OTHER = 4712L;

    @Test
    void aCrystalLandingOnAFreshPlacementIsClaimed() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        assertTrue(c.added(7, CELL, 103));
        assertTrue(c.isClaimed(7));
        assertEquals(1, c.claimedTotal());
    }

    @Test
    void aCrystalOnAnUnplacedCellIsNotClaimed() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        assertFalse(c.added(7, OTHER, 101));
        assertTrue(c.claimedIds().isEmpty());
    }

    @Test
    void anOpponentCrystalOnAPreviouslyPlacedCellIsNotOurs() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        assertTrue(c.added(7, CELL, 102));
        assertFalse(c.added(8, CELL, 150));
        assertFalse(c.added(9, CELL, 103));
        assertEquals(Set.of(7), c.claimedIds());
    }

    @Test
    void aPlacementClaimsAtMostOneCrystalAndTwoPlacementsAtMostTwo() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        c.placed(CELL, 101);
        assertTrue(c.added(1, CELL, 102));
        assertTrue(c.added(2, CELL, 103));
        assertFalse(c.added(3, CELL, 104));
        assertEquals(Set.of(1, 2), c.claimedIds());
    }

    @Test
    void aPlacementNeverFollowedByACrystalExpiresAfterTheWindow() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        assertFalse(c.added(1, CELL, 100 + CrystalClaims.WINDOW_TICKS + 1));
        c.placed(CELL, 200);
        assertTrue(c.added(2, CELL, 200 + CrystalClaims.WINDOW_TICKS));
    }

    @Test
    void aCrystalCannotBeClaimedByAPlacementSentAfterIt() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 105);
        assertFalse(c.added(1, CELL, 100));
    }

    @Test
    void anAddedIdIsClaimedOnce() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        c.placed(CELL, 100);
        assertTrue(c.added(1, CELL, 101));
        assertTrue(c.added(1, CELL, 101));
        assertEquals(1, c.claimedTotal());
        assertTrue(c.added(2, CELL, 101));
    }

    @Test
    void ourCrystalBrokenInTheTickItAppearedHasItsHitCountedAsOurs() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        c.added(77, CELL, 102);
        List<DamageEvent> damage = List.of(
            new DamageEvent(102, DamageKind.CRYSTAL, AttackerKind.PLAYER, "sparring", 20, 4, false));
        Set<Integer> ours = OwnHits.indexes(damage, List.of(new HitSource(102, 77)), c.claimedIds());
        assertEquals(Set.of(0), ours);
    }

    @Test
    void anOpponentCrystalOnOurEarlierCellStaysTheOpponents() {
        CrystalClaims c = new CrystalClaims();
        c.placed(CELL, 100);
        c.added(77, CELL, 102);
        c.added(88, CELL, 160);
        List<DamageEvent> damage = List.of(
            new DamageEvent(161, DamageKind.CRYSTAL, AttackerKind.PLAYER, "sparring", 20, 0, true));
        assertTrue(OwnHits.indexes(damage, List.of(new HitSource(161, 88)), c.claimedIds()).isEmpty());
    }

    @Test
    void itIsClearableAndBounded() {
        CrystalClaims c = new CrystalClaims();
        for (int i = 0; i < CrystalClaims.CLAIMED_KEPT + 50; i++) {
            c.placed(CELL, i);
            c.added(i, CELL, i);
        }
        assertEquals(CrystalClaims.CLAIMED_KEPT, c.claimedIds().size());
        assertEquals(CrystalClaims.CLAIMED_KEPT + 50, c.claimedTotal());
        c.clear();
        assertTrue(c.claimedIds().isEmpty());
    }

    @Test
    void aCrystalSeenAsForeignIsNotClaimedLaterByAPlacementSentAfterward() {
        CrystalClaims c = new CrystalClaims();
        assertFalse(c.added(5, CELL, 100));
        c.placed(CELL, 101);
        assertFalse(c.added(5, CELL, 102));
    }
}
