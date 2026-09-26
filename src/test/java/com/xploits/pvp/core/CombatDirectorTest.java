package com.xploits.pvp.core;

import com.xploits.shared.core.i18n.Msg;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDirectorTest {
    private static final int APPROACH = 6;

    private static final Map<Resource, Integer> FULL = Map.of(
        Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64,
        Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1,
        Resource.STRING, 8, Resource.SLABS, 8);

    /** A whole surround: the four horizontal neighbours of the target are mineable (M1). */
    private static final int SURROUNDED_SIDES = 4;

    /** Distance at which SURROUNDED is ENTERED: the real limit minus the inward band (I1). */
    private static final double CITY_ENTER =
        CombatDirector.AUTO_CITY_BREAK_RANGE - CombatDirector.NEAR_LIMIT_BAND;

    /** The same for the target bound, the other of auto-city's two. */
    private static final double CITY_TARGET_ENTER =
        CombatDirector.AUTO_CITY_TARGET_RANGE - CombatDirector.NEAR_LIMIT_BAND;

    /** Enemy close (3.0), on foot, clean, with all the gear on you and nothing aimed at you. */
    private static CombatSnapshot surface() {
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, FULL)
            .withTargetId("enemy");
    }

    /**
     * There is no target this tick, but you are still you. It is what a real flicker produces: the
     * adapter reads your inventory and your health even with nobody to look at, so losing the target
     * is not the same as {@link CombatSnapshot#none()}, which also has no totems.
     */
    private static CombatSnapshot noTarget() {
        return Snapshots.of(false, 0, 0, 0, false, false, false, 2, FULL);
    }

    private static CombatSnapshot with(CombatSnapshot base, boolean surrounded, boolean burrowed,
                                       boolean targetGliding, boolean selfGliding) {
        // cityBlockDistance is not relevant for these tests (none touches the boundary of
        // AUTO_CITY_BREAK_RANGE): using the base snapshot's own targetDistance is enough
        // for "surrounded" to classify SURROUNDED when it should.
        return new CombatSnapshot(base.hasTarget(), base.targetDistance(),
            surrounded ? SURROUNDED_SIDES : 0, base.targetDistance(),
            burrowed, targetGliding, selfGliding, base.selfTotems(), base.resources(),
            base.targetId(), base.hostilesInCrystalRange(), base.selfTotalHealth(),
            base.incomingDamage(), base.selfInHole(), base.selfOnGround(), base.selfYChanged(),
            base.crystalAuraAntiSuicide());
    }

    /**
     * Lets the director settle in the phase the snapshot asks for. With room for the longest
     * slack of all (the one for leaving CHASE after landing).
     */
    private static Plan settle(CombatDirector director, CombatSnapshot snapshot) {
        Plan plan = null;
        for (int i = 0; i < CombatDirector.GLIDE_EXIT_HOLD_TICKS + 5; i++) {
            plan = director.tick(snapshot, APPROACH);
        }
        return plan;
    }

    /**
     * Shows the director a target that is really moving away -1.5 blocks gained over the window
     * of {@link RetreatWatch}, above its START_GAIN- and leaves it at {@code endDistance}.
     * Returns the plan of the last tick.
     */
    private static Plan pullingAwayTo(CombatDirector director, CombatSnapshot base, double endDistance) {
        Plan plan = null;
        double start = endDistance - 1.5;
        for (int i = 0; i <= RetreatWatch.WINDOW_TICKS; i++) {
            double distance = start + (endDistance - start) * i / RetreatWatch.WINDOW_TICKS;
            plan = director.tick(base.withTargetDistance(distance), APPROACH);
        }
        return plan;
    }

    private static boolean enables(Plan plan, ManagedModule module) {
        return plan.enable().contains(module);
    }

    private static boolean skips(Plan plan, ManagedModule module) {
        return plan.skipped().stream().anyMatch(s -> s.module().equals(module));
    }

    // --- §4.1: the precedence ---

    @Test
    void withoutATargetItIsOutOfCombatAndAsksForNothing() {
        Plan plan = new CombatDirector().tick(CombatSnapshot.none(), APPROACH);
        assertEquals(CombatState.NO_COMBAT, plan.state());
        assertTrue(plan.enable().isEmpty());
    }

    @Test
    void aTargetNearAndOnFootIsSurface() {
        Plan plan = settle(new CombatDirector(), surface());
        assertEquals(CombatState.SURFACE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void aTargetBeyondTheClassifyRangeIsNotACombatAtAll() {
        // §9: from 16 to 10. No managed module goes beyond 10, so the 10-16 band only produced
        // phases with a name and without modules.
        CombatSnapshot far = surface().withTargetDistance(CombatDirector.CLASSIFY_TARGET_RANGE + 0.5);
        assertEquals(CombatState.NO_COMBAT, settle(new CombatDirector(), far).state());
    }

    @Test
    void theClassifyRangeConstantIsFixedAtTen() {
        assertEquals(10.0, CombatDirector.CLASSIFY_TARGET_RANGE, 0.0,
            "§9: no managed module goes beyond 10");
    }

    @Test
    void atExactlyTheClassifyRangeThereIsStillACombat() {
        CombatSnapshot atBoundary = surface().withTargetDistance(10.0);
        assertEquals(CombatState.APPROACH, settle(new CombatDirector(), atBoundary).state());
    }

    // --- §4.1: CHASE is only triggered by the target, and only out of crystal range ---

    @Test
    void aGlidingTargetOutOfCrystalRangeIsAChase() {
        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        Plan plan = settle(new CombatDirector(), flying);
        assertEquals(CombatState.CHASE, plan.state());
        assertTrue(plan.enable().isEmpty(),
            "§4.2: auto-web never places at elytra speed; enabling it was pretending to do something");
    }

    @Test
    void aGlidingTargetWithinCrystalRangeIsAnOrdinaryFight() {
        // §11: the target gliding at 2 blocks is SURFACE, not CHASE. Flying and right next to
        // you is a normal fight, and the crystals get to them just the same.
        CombatSnapshot diving = with(surface(), false, false, true, false).withTargetDistance(2.0);
        Plan plan = settle(new CombatDirector(), diving);
        assertEquals(CombatState.SURFACE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void atExactlyTheCrystalRangeAGlidingTargetIsStillAnOrdinaryFight() {
        CombatSnapshot atBoundary = with(surface(), false, false, true, false)
            .withTargetDistance(CombatDirector.CRYSTAL_RANGE);
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), atBoundary).state(),
            "the comparison is strictly greater than: at the threshold the crystals still get in");
    }

    @Test
    void youGlidingChangesNoPhaseAtAll() {
        // §4.1: on this server people fly almost all the time, so selfGliding() left the director
        // in CHASE -the phase that does the least- most of the time. Your flying says
        // nothing about the enemy.
        for (CombatState expected : new CombatState[]{CombatState.SURFACE, CombatState.SURROUNDED,
            CombatState.BURROWED, CombatState.APPROACH}) {
            CombatSnapshot onFoot = switch (expected) {
                case SURROUNDED -> with(surface(), true, false, false, false);
                case BURROWED -> with(surface(), false, true, false, false);
                case APPROACH -> surface().withTargetDistance(8.0);
                default -> surface();
            };
            CombatSnapshot flying = with(onFoot, onFoot.targetSurroundSides() >= CombatDirector.SURROUND_MIN_SIDES,
                onFoot.targetBurrowed(), false, true);

            assertEquals(expected, settle(new CombatDirector(), onFoot).state());
            assertEquals(expected, settle(new CombatDirector(), flying).state(),
                "your flying must not change " + expected);
        }
    }

    // --- §4.1: BURROWED requires anvil range and is measured by protection, not by solidity ---

    @Test
    void aBurrowedTargetWithinAnvilRangeIsBurrowed() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, false, false));
        assertEquals(CombatState.BURROWED, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL));
    }

    @Test
    void aBurrowedTargetTwelveBlocksAwayIsNotBurrowed() {
        // §11: someone burrowed at 12 is not a phase, it is an obstacle. AutoAnvil works at 4: the thing
        // to do is close in, not turn off the aura and stand still. At 12 there is not even a combat (§9).
        CombatSnapshot farBurrowed = with(surface(), false, true, false, false).withTargetDistance(12.0);
        Plan plan = settle(new CombatDirector(), farBurrowed);

        assertEquals(CombatState.NO_COMBAT, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_ANVIL));
    }

    @Test
    void aBurrowedTargetJustBeyondAnvilRangeIsNotBurrowedEither() {
        CombatSnapshot justBeyond = with(surface(), false, true, false, false)
            .withTargetDistance(Math.nextUp(CombatDirector.AUTO_ANVIL_TARGET_RANGE));
        Plan plan = settle(new CombatDirector(), justBeyond);

        assertEquals(CombatState.SURFACE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_ANVIL), "auto-anvil does not reach: it is not enabled");
    }

    @Test
    void justInsideTheAnvilBandItIsBurrowed() {
        // I1: to ENTER you have to be half a block inside auto-anvil's real reach.
        CombatSnapshot inside = with(surface(), false, true, false, false)
            .withTargetDistance(CombatDirector.AUTO_ANVIL_TARGET_RANGE - CombatDirector.NEAR_LIMIT_BAND);
        assertEquals(CombatState.BURROWED, settle(new CombatDirector(), inside).state());
    }

    @Test
    void jumpingNextToABurrowedEnemyDoesNotMakeThePhaseOscillate() {
        // I1, the fight: you stand at 3.4 from someone burrowed and jump. Going up 1.25 in Y lengthens the
        // distance to 3.62 for about six ticks -more than the two of BLOCK_HOLD_TICKS-, and with the
        // earlier bare threshold the phase dropped out of BURROWED and came back, aborting the auto-anvil
        // sequence mid-fall. Against someone burrowed, jumping is normal.
        CombatDirector director = new CombatDirector();
        CombatSnapshot burrowed = with(surface(), false, true, false, false).withTargetDistance(3.4);
        assertEquals(CombatState.BURROWED, settle(director, burrowed).state());

        for (int i = 0; i < 6; i++) {
            Plan plan = director.tick(burrowed.withTargetDistance(3.62), APPROACH);
            assertEquals(CombatState.BURROWED, plan.state(), "tick " + i + " of the jump");
            assertTrue(enables(plan, ManagedModules.AUTO_ANVIL), "tick " + i + ": the anvil stays");
        }
    }

    @Test
    void aBurrowedEnemyBeyondTheAnvilRangeLeavesThePhase() {
        // The band goes inwards and not outwards: past auto-anvil's real reach it leaves,
        // because enabling something that does not reach is the silent failure principle 10 forbids.
        CombatDirector director = new CombatDirector();
        CombatSnapshot burrowed = with(surface(), false, true, false, false).withTargetDistance(3.4);
        assertEquals(CombatState.BURROWED, settle(director, burrowed).state());

        CombatSnapshot away = burrowed.withTargetDistance(Math.nextUp(CombatDirector.AUTO_ANVIL_TARGET_RANGE));
        assertEquals(CombatState.SURFACE, settle(director, away).state());
    }

    @Test
    void theAnvilRangeConstantIsFixedAtFour() {
        assertEquals(4.0, CombatDirector.AUTO_ANVIL_TARGET_RANGE, 0.0,
            "4 is auto-anvil's default target-range");
    }

    @Test
    void standingOnASlabIsNotBurrowedButStandingOnObsidianIs() {
        // §11 and §2: blocksMovement() accepted a bottom slab (0.833 of half-side), so
        // standing on a slab, a stair, a chest or a trapdoor was classified as
        // BURROWED. The core already receives the answer to the right question -"does it protect it from a
        // crystal?", blast >= 600 and full cube-, so here it is pinned that the phase comes from that
        // flag and from nothing else.
        CombatSnapshot onASlab = with(surface(), false, false, false, false);
        CombatSnapshot onObsidian = with(surface(), false, true, false, false);

        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), onASlab).state());
        assertEquals(CombatState.BURROWED, settle(new CombatDirector(), onObsidian).state());
    }

    // --- §4.2.1: SURROUNDED, with auto-city's two bounds intact ---

    @Test
    void aSurroundedTargetCallsForAutoCityAndCrystalAura() {
        Plan plan = settle(new CombatDirector(), with(surface(), true, false, false, false));
        assertEquals(CombatState.SURROUNDED, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    /**
     * Surrounded according to Meteor (`getCityBlock() != null`), target always close (3.0, well below
     * {@code approachDistance}), varying only the REAL distance to the surround block -not to the
     * target- (spec §4.2.1, corrected: before the correction this bound was applied, wrongly, to
     * targetDistance).
     */
    private static CombatSnapshot surroundedAt(double cityBlockDistance) {
        return Snapshots.of(true, 3.0, SURROUNDED_SIDES, cityBlockDistance, false, false, false, 2, FULL);
    }

    @Test
    void surroundedButBeyondAutoCityRangeIsNotSurrounded() {
        // CRITICAL: getCityBlock() sees up to 6 blocks, but auto-city turns itself off -with an error in
        // chat- beyond its break-range (4.5 by default). In that middle band the
        // director must not ask for SURROUNDED: it would turn auto-city on and off endlessly (spec §4.2.1).
        CombatSnapshot beyond = surroundedAt(CombatDirector.AUTO_CITY_BREAK_RANGE + 1.0);
        Plan plan = settle(new CombatDirector(), beyond);

        assertEquals(CombatState.SURFACE, plan.state(), "within approach-distance, it falls to SURFACE");
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void surroundedAndWithinAutoCityRangeIsSurrounded() {
        CombatSnapshot within = surroundedAt(CombatDirector.AUTO_CITY_BREAK_RANGE - 1.0);
        Plan plan = settle(new CombatDirector(), within);

        assertEquals(CombatState.SURROUNDED, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void atExactlyTheAutoCityRangeYouDoNotEnterSurroundedButYouDoStayInIt() {
        // I1: the band of these bounds goes inwards. Right at the real break-range it is not ENTERED
        // -it takes half a block closer-, but if you were already inside it is not LEFT: that is where the
        // slack is, and auto-city never ends up out of its reach, which is where it turns itself off.
        CombatSnapshot atBoundary = surroundedAt(CombatDirector.AUTO_CITY_BREAK_RANGE);
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), atBoundary).state());

        CombatDirector director = new CombatDirector();
        assertEquals(CombatState.SURROUNDED, settle(director, surroundedAt(CITY_ENTER)).state());
        assertEquals(CombatState.SURROUNDED, settle(director, atBoundary).state(),
            "once inside, the real limit still holds");
    }

    @Test
    void justBeyondTheAutoCityRangeItIsNoLongerSurrounded() {
        CombatSnapshot justBeyond = surroundedAt(Math.nextUp(CombatDirector.AUTO_CITY_BREAK_RANGE));
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), justBeyond).state(),
            "one step above the threshold is no longer SURROUNDED");
    }

    @Test
    void aTargetCloseWithACityBlockOnTheFarSideIsNotSurrounded() {
        // Real counterexample of the CRITICAL: player at (0.5, 0, 0.5), target at (4.5, 0, 0.5)
        // -distance to the target 4.0, "close" under the old judgement-, surround block at
        // (5, 0, 0) -on the side opposite the player relative to the target-, squared distance
        // 20.5 > 4.5² = 20.25. With the old judgement (proxy: distance to the target) this was
        // declared SURROUNDED and auto-city turned itself off, with an error, every tick.
        double realBlockDistance = Math.sqrt(20.5);
        CombatSnapshot snapshot = Snapshots.of(true, 4.0, SURROUNDED_SIDES, realBlockDistance, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SURFACE, plan.state(),
            "target close but the real surround block is out of auto-city's reach");
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void theAutoCityRangeConstantIsFixedAtFourPointFive() {
        // Pins the value, not only its existence: without this, changing the constant to 2, 3 or 4 leaves
        // the rest of the tests green because they are all expressed in terms of it.
        assertEquals(4.5, CombatDirector.AUTO_CITY_BREAK_RANGE, 0.0,
            "4.5 is auto-city's default break-range in Meteor");
    }

    @Test
    void aCityBlockAtTheLiteralFourIsSurrounded() {
        // With literals, not with the constants: if someone moves AUTO_CITY_BREAK_RANGE or the band,
        // this test catches it -unlike surroundedAt(), which would move with them-. 4.0 is
        // the default 4.5 minus the half block of band.
        CombatSnapshot atLiteralBoundary = Snapshots.of(true, 3.0, SURROUNDED_SIDES, 4.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SURROUNDED, settle(new CombatDirector(), atLiteralBoundary).state());
    }

    @Test
    void aCityBlockJustBeyondTheLiteralFourIsNotSurrounded() {
        CombatSnapshot justBeyond = Snapshots.of(true, 3.0, SURROUNDED_SIDES, 4.01, false, false, false, 2, FULL);
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), justBeyond).state());
    }

    /**
     * CRITICAL (third correction): AUTO_CITY_BREAK_RANGE alone is not enough. AutoCity.onTick()
     * first checks TargetUtils.isBadTarget(target, targetRange) -distance to the TARGET, not to the
     * block- and turns itself off if it fails, before looking at the block at all.
     */
    @Test
    void targetBeyondAutoCityTargetRangeIsNotSurroundedEvenWithTheBlockClose() {
        CombatSnapshot snapshot = Snapshots.of(true, CombatDirector.AUTO_CITY_TARGET_RANGE + 0.5,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SURFACE, plan.state(),
            "block in reach but the real target is out of auto-city's target-range");
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void targetWithinAutoCityTargetRangeAndBlockCloseIsSurrounded() {
        CombatSnapshot snapshot = Snapshots.of(true, CombatDirector.AUTO_CITY_TARGET_RANGE - 0.5,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SURROUNDED, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void atExactlyTheAutoCityTargetRangeYouDoNotEnterSurroundedButYouDoStayInIt() {
        // The second SURROUNDED bound has the same inward band as the first (I1).
        CombatSnapshot atBoundary = Snapshots.of(true, CombatDirector.AUTO_CITY_TARGET_RANGE,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), atBoundary).state());

        CombatDirector director = new CombatDirector();
        CombatSnapshot inside = Snapshots.of(true, CITY_TARGET_ENTER,
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SURROUNDED, settle(director, inside).state());
        assertEquals(CombatState.SURROUNDED, settle(director, atBoundary).state(),
            "once inside, the real limit still holds");
    }

    @Test
    void justBeyondTheAutoCityTargetRangeItIsNoLongerSurrounded() {
        CombatSnapshot justBeyond = Snapshots.of(true, Math.nextUp(CombatDirector.AUTO_CITY_TARGET_RANGE),
            SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), justBeyond).state(),
            "one step above the threshold is no longer SURROUNDED");
    }

    @Test
    void theAutoCityTargetRangeConstantIsFixedAtFivePointFive() {
        assertEquals(5.5, CombatDirector.AUTO_CITY_TARGET_RANGE, 0.0,
            "5.5 is auto-city's default target-range in Meteor");
    }

    @Test
    void aTargetAtTheLiteralFiveIsSurrounded() {
        // 5.0 is the default target-range (5.5) minus the half block of band.
        CombatSnapshot atLiteralBoundary = Snapshots.of(true, 5.0, SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SURROUNDED, settle(new CombatDirector(), atLiteralBoundary).state());
    }

    @Test
    void aTargetJustBeyondTheLiteralFiveIsNotSurrounded() {
        CombatSnapshot justBeyond = Snapshots.of(true, 5.01, SURROUNDED_SIDES, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), justBeyond).state());
    }

    @Test
    void burrowedBeatsSurroundedWhenBothAreTrue() {
        Plan plan = settle(new CombatDirector(), with(surface(), true, true, false, false));
        assertEquals(CombatState.BURROWED, plan.state());
    }

    @Test
    void chaseBeatsBurrowedWhenBothAreTrueAndHeIsFar() {
        CombatSnapshot snapshot = with(surface(), false, true, true, false).withTargetDistance(8.0);
        assertEquals(CombatState.CHASE, settle(new CombatDirector(), snapshot).state());
    }

    // --- §4.2: the share-out by phase ---

    @Test
    void surfaceAsksForTheAuraAndTheTrapWhenHeIsWithinTrapRange() {
        Plan plan = settle(new CombatDirector(), surface());
        assertEquals(CombatState.SURFACE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "at 3.0 auto-trap reaches");
        assertFalse(enables(plan, ManagedModules.AUTO_WEB),
            "§4.3: right next to you and not leaving, the web steals the aura's best crystal position");
    }

    @Test
    void surfaceDoesNotAskForTheTrapBeyondItsRange() {
        // §10: no module is enabled beyond its real reach. AutoTrap works at 3.
        CombatSnapshot snapshot = surface().withTargetDistance(Math.nextUp(CombatDirector.AUTO_TRAP_TARGET_RANGE));
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SURFACE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
        assertFalse(skips(plan, ManagedModules.AUTO_TRAP),
            "it does not reach: it is not a resource omission, it is simply not asked for");
    }

    @Test
    void theTrapRangeConstantIsFixedAtThree() {
        assertEquals(3.0, CombatDirector.AUTO_TRAP_TARGET_RANGE, 0.0,
            "3 is auto-trap's default target-range");
    }

    @Test
    void approachAsksForNothingAtAll() {
        // §4.2: APPROACH stays as a reporting label. Between 6 and 16 blocks there is nothing useful
        // to enable, and surround -the only thing it asked for before- locked you in obsidian while
        // you ran and spent the obsidian auto-trap was going to need.
        Plan plan = settle(new CombatDirector(), surface().withTargetDistance(8.0));

        assertEquals(CombatState.APPROACH, plan.state());
        assertTrue(plan.enable().isEmpty());
        assertFalse(enables(plan, ManagedModules.SURROUND));
    }

    @Test
    void burrowedAsksForTheAnvilAndTheTrapWhenTheTrapReaches() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, false, false));
        assertEquals(CombatState.BURROWED, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "the trap is for when they come out of the burrow");
        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA),
            "with no other hostiles in crystal range, against someone burrowed the aura is of no use");
    }

    @Test
    void burrowedDoesNotAskForTheTrapBeyondItsRange() {
        // BURROWED goes up to 4 and auto-trap to 3: in between it does not reach.
        CombatSnapshot snapshot = with(surface(), false, true, false, false).withTargetDistance(3.5);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.BURROWED, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
    }

    // --- §4.3: auto-web does not steal the aura's spot ---

    @Test
    void theWebDoesNotGoUpJustBecauseHeIsFourBlocksAway() {
        // I2: the "or is more than 3 blocks away" half of the gate went entirely, and not for a band.
        // Its reason was that beyond 3 the cell it webs would no longer be the next crystal's, and
        // that is false: the aura's place-range is 4.5, so in the whole band left under the
        // new bound the aura still wants that cell. Standing still at 4 blocks there is no web.
        Plan plan = settle(new CombatDirector(), surface().withTargetDistance(4.0));
        assertEquals(CombatState.SURFACE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_WEB));
    }

    @Test
    void aTargetDancingAroundThreeBlocksNoLongerFlipsTheWeb() {
        // I2, the measurement: with the target hovering at 3.0, 39 state changes were counted in 40
        // ticks, because the bare threshold short-circuited with an || the dead band RetreatWatch
        // does look after. Without that threshold there is nothing to cross.
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(3.0));
        for (int i = 0; i < 40; i++) {
            Plan plan = director.tick(surface().withTargetDistance(i % 2 == 0 ? 2.9 : 3.1), APPROACH);
            assertFalse(enables(plan, ManagedModules.AUTO_WEB), "tick " + i + ": dancing is not leaving");
        }
    }

    @Test
    void theWebDoesNotGoUpBeyondItsPlaceRangeEvenIfHeIsPullingAway() {
        // I5: SURFACE goes up to 7 and AutoWeb's place-range is 4. Between 4 and 7 the director
        // enabled it and the module placed nothing: the silent failure principle 10 forbids, and
        // the only managed one that had been left without an upper bound.
        CombatDirector director = new CombatDirector();
        Plan plan = pullingAwayTo(director, surface(), 5.0);

        assertEquals(CombatState.SURFACE, plan.state());
        assertTrue(director.targetRetreating(), "precondition: the director sees them moving away");
        assertFalse(enables(plan, ManagedModules.AUTO_WEB),
            "they are leaving, but at 5 blocks the web does not reach");
    }

    @Test
    void theWebGoesUpWithinItsPlaceRangeWhenHeIsPullingAway() {
        CombatDirector director = new CombatDirector();
        Plan plan = pullingAwayTo(director, surface(), CombatDirector.AUTO_WEB_PLACE_RANGE);

        assertTrue(director.targetRetreating(), "precondition: the director sees them moving away");
        assertTrue(enables(plan, ManagedModules.AUTO_WEB),
            "right at the place-range it still places: the comparison is less-than-or-equal");
    }

    @Test
    void theWebGoesUpPointBlankOnlyIfHeIsSustainedlyPullingAway() {
        CombatDirector director = new CombatDirector();
        // It settles right next to you and still: the web must not be enabled.
        Plan still = settle(director, surface().withTargetDistance(2.0));
        assertFalse(enables(still, ManagedModules.AUTO_WEB));

        // Now it gains ground little by little without leaving the trap range: 0.1 blocks per
        // tick is 1 block over the RetreatWatch window, exactly what declares "moving away".
        Plan plan = null;
        double distance = 2.0;
        for (int i = 0; i < RetreatWatch.WINDOW_TICKS + 1; i++) {
            distance += 0.1;
            plan = director.tick(surface().withTargetDistance(Math.min(distance, 3.0)), APPROACH);
        }

        assertTrue(director.targetRetreating(), "precondition: the director sees them moving away");
        assertTrue(enables(plan, ManagedModules.AUTO_WEB),
            "the web is for stopping them from leaving, and they are leaving");
    }

    @Test
    void theWebDoesNotGoUpPointBlankJustBecauseHeMoves() {
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(2.0));

        // Orbiting around the enemy changes the distance on every tick, but gains no ground.
        Plan plan = null;
        for (int i = 0; i < 40; i++) {
            plan = director.tick(surface().withTargetDistance(i % 2 == 0 ? 1.6 : 2.4), APPROACH);
            assertFalse(enables(plan, ManagedModules.AUTO_WEB), "tick " + i + ": moving is not leaving");
        }
        assertFalse(director.targetRetreating());
    }

    // --- §4.4: the aura is not turned off by the phase if there is someone to crystal ---

    @Test
    void withAnotherHostileInCrystalRangeTheAuraIsWantedAgainstTheBurrowedOne() {
        // §11: the obvious bait -one burrows, the other crystals you, and the director turns off your aura
        // against the second-. crystal-aura fights against everyone at once.
        CombatSnapshot snapshot = with(surface(), false, true, false, false).withHostiles(1);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.BURROWED, plan.state(), "the phase is still the target's");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL), "and what the phase asks for is still there");
    }

    @Test
    void withAHostileInCrystalRangeTheAuraIsWantedEvenInAChase() {
        CombatSnapshot snapshot = with(surface(), false, false, true, false)
            .withTargetDistance(8.0).withHostiles(2);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.CHASE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void withoutUnprotectedHostilesThePhaseDecidesTheAuraAsBefore() {
        Plan plan = settle(new CombatDirector(), with(surface(), false, true, false, false).withHostiles(0));
        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void theOneWhoBurrowsHimselfInFrontOfYouStillKeepsTheAuraOn() {
        // C1, the whole fight: you are at 3.5 from a guy who is losing and burrows -the server's
        // standard move-. The phase goes to BURROWED, he keeps placing crystals on you from
        // inside the burrow, and with the earlier rule -which only counted UNPROTECTED hostiles-
        // the count was zero and the ledger turned off your autobreak against the only one who could kill you.
        // Now he himself is counted: burrowed or not, his crystals get to you just the same and breaking costs
        // you none.
        CombatSnapshot snapshot = with(surface(), false, true, false, false)
            .withTargetDistance(3.5)
            .withHostiles(1);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.BURROWED, plan.state(), "the phase is still the target's");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA),
            "C1: turning it off costs the fight; leaving it on too long costs a few crystals");
    }

    @Test
    void aSurroundedTargetAlsoCountsForTheAura() {
        // The other half of C1: "protected" included the surrounded one, and the surrounded one crystals you just the same.
        CombatSnapshot snapshot = with(surface(), true, false, false, false).withHostiles(1);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SURROUNDED, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void theCrystalRangeConstantIsFixedAtFourPointFive() {
        // M2: 4.5 is CrystalAura's default place-range and break-range, checked in its
        // sources. The 10 is its target-range, which only says whom it looks at.
        assertEquals(4.5, CombatDirector.CRYSTAL_RANGE, 0.0,
            "M2: CrystalAura's place-range and break-range, not the assumed 5.5");
    }

    @Test
    void withTheShortestApproachTheAuraStillCoversUpToCrystalRange() {
        // M3: with approach-distance at 2, the phase stops asking for the aura between 3 and the crystal
        // range -there the phase is APPROACH, which enables nothing-. C1 closes the gap: at that
        // distance the target is a hostile in crystal range and the count sees it.
        CombatSnapshot snapshot = surface().withTargetDistance(4.0).withHostiles(1);
        CombatDirector director = new CombatDirector();
        Plan plan = null;
        for (int i = 0; i < 20; i++) plan = director.tick(snapshot, 2);

        assertEquals(CombatState.APPROACH, plan.state(), "with approach 2, at 4 blocks is far");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA),
            "M3: the setting's floor leaves no gap without the aura inside crystal range");
    }

    // --- M1: an obsidian wall is not a surround ---

    @Test
    void anEnemyStandingNextToAWallIsNotSurrounded() {
        // getCityBlock() only says "there is ONE mineable block next to them", so an enemy standing
        // by the obsidian wall of any base -or by the obsidian your own
        // auto-trap has just placed- classified as SURROUNDED and the director started mining the wall.
        CombatSnapshot nextToAWall = Snapshots.of(true, 3.0, 1, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), nextToAWall);

        assertEquals(CombatState.SURFACE, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_CITY), "there is no wall to mine");
    }

    @Test
    void anEnemyInACornerWithTwoSidesIsNotSurroundedEither() {
        CombatSnapshot inACorner = Snapshots.of(true, 3.0, 2, 1.0, false, false, false, 2, FULL);
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), inACorner).state());
    }

    @Test
    void aSurroundWithOneSideAlreadyBrokenIsStillASurround() {
        // Three out of four: it is the most common case of all, to keep mining the one you had already started.
        CombatSnapshot threeSides = Snapshots.of(true, 3.0, 3, 1.0, false, false, false, 2, FULL);
        Plan plan = settle(new CombatDirector(), threeSides);

        assertEquals(CombatState.SURROUNDED, plan.state());
        assertTrue(enables(plan, ManagedModules.AUTO_CITY));
    }

    // --- I3: three placers, a single obsidian stack ---

    /** In the hole, threatened, with the enemy on top of you and whatever obsidian it is given. */
    private static CombatSnapshot inAHoleWithObsidian(int obsidian) {
        Map<Resource, Integer> resources = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, obsidian,
            Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources)
            .withTargetId("enemy")
            .withDefense(10.0, 0.0, true, true);
    }

    @Test
    void withEightObsidianTheFourPlacersDoNotAllGetApproved() {
        // I3, the situation: in a hole, threatened and with the enemy on top of you, these come up at once:
        // auto-trap (minimum 8), surround (4), hole-filler (1) and anti-anvil (1). With eight obsidian they
        // ask for fourteen between them, all four swap to the same stack on the same tick and none
        // completes its job. The share-out is defensive before offensive and cheap before expensive.
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(8));

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER), "the cheapest and the most defensive");
        assertTrue(enables(plan, ManagedModules.SURROUND), "1 + 4 fit in 8");
        assertTrue(enables(plan, ManagedModules.ANTI_ANVIL), "1 + 4 + 1 fit in 8");
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP), "2 are left and it needs 8");
    }

    @Test
    void theModuleLeftOutOfTheObsidianIsSaidOutLoud() {
        // What is not acceptable is approving more than there is and keeping quiet about it: before, skipped came out empty.
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(8));

        assertTrue(skips(plan, ManagedModules.AUTO_TRAP));
        Msg reason = plan.skipped().stream()
            .filter(skipped -> skipped.module().equals(ManagedModules.AUTO_TRAP))
            .map(Skipped::reason).findFirst().orElse(null);
        assertEquals(Msg.of(PvpText.SHORTAGE_SHARED, "have", 8,
                "others", Msg.of(PvpText.JOIN_AND,
                    "first", Msg.of(PvpText.JOIN_AND, "first", "hole-filler", "second", "surround"),
                    "second", "anti-anvil"),
                "left", 2, "minimum", 8), reason,
            "the reason has to name who took the obsidian");
    }

    @Test
    void withEnoughObsidianForTheFourOfThemTheFourGoUp() {
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(14));

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER));
        assertTrue(enables(plan, ManagedModules.SURROUND));
        assertTrue(enables(plan, ManagedModules.ANTI_ANVIL));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "14 is exactly 1 + 4 + 1 + 8");
    }

    @Test
    void withFourObsidianOnlyTheCheapestDefensiveOnesGetTheirShare() {
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(4));

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER), "filling the hole costs one");
        assertFalse(enables(plan, ManagedModules.SURROUND), "3 are left and it needs 4");
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
    }

    // --- the anti- modules place something too, and the filter now sees it ---

    /** Threatened out of a hole with the enemy close, carrying exactly this. */
    private static CombatSnapshot threatenedWith(Map<Resource, Integer> resources) {
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources)
            .withTargetId("enemy")
            .withDefense(14.0, 6.0, false, true);
    }

    private static Msg reasonFor(Plan plan, ManagedModule module) {
        return plan.skipped().stream()
            .filter(skipped -> skipped.module().equals(module))
            .map(Skipped::reason).findFirst().orElse(null);
    }

    @Test
    void antiBedWithoutStringIsSkippedForTheShortage() {
        // AntiBed places string (InvUtils.findInHotbar(Items.STRING)): turned on without any it can
        // place nothing, and saying it is on would be the silent failure §10 forbids.
        Plan plan = settle(new CombatDirector(), threatenedWith(Map.of(Resource.CRYSTALS, 12, Resource.STRING, 0)));

        assertEquals(CombatPosture.THREATENED, plan.posture(), "precondition");
        assertFalse(enables(plan, ManagedModules.ANTI_BED));
        assertEquals(Msg.of(PvpText.SHORTAGE, "have", 0, "minimum", 1), reasonFor(plan, ManagedModules.ANTI_BED));
    }

    @Test
    void antiBedWithOneStringComesUp() {
        Plan plan = settle(new CombatDirector(), threatenedWith(Map.of(Resource.CRYSTALS, 12, Resource.STRING, 1)));

        assertTrue(enables(plan, ManagedModules.ANTI_BED));
        assertFalse(skips(plan, ManagedModules.ANTI_BED));
    }

    @Test
    void antiAnvilSharesTheObsidianAndHoleFillerGoesFirst() {
        // AntiAnvil places obsidian between you and the anvil: it draws from the same stack as
        // hole-filler, and with one block only one of them can have it. hole-filler is first in the
        // share-out order.
        Plan plan = settle(new CombatDirector(), threatenedWith(Map.of(Resource.CRYSTALS, 12, Resource.OBSIDIAN, 1)));

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER));
        assertFalse(enables(plan, ManagedModules.ANTI_ANVIL));
        assertEquals(Msg.of(PvpText.SHORTAGE_SHARED, "have", 1, "others", "hole-filler", "left", 0, "minimum", 1),
            reasonFor(plan, ManagedModules.ANTI_ANVIL));
    }

    @Test
    void antiAnchorNeedsASlab() {
        Plan without = settle(new CombatDirector(), threatenedWith(Map.of(Resource.CRYSTALS, 12)));
        assertFalse(enables(without, ManagedModules.ANTI_ANCHOR));

        Plan with = settle(new CombatDirector(), threatenedWith(Map.of(Resource.CRYSTALS, 12, Resource.SLABS, 1)));
        assertTrue(enables(with, ManagedModules.ANTI_ANCHOR));
    }

    // --- I4: the defensive axis's resource memory does not freeze in NO_COMBAT ---

    @Test
    void holeFillerDoesNotFlickerOutOfCombatWhileTheObsidianComesAndGoes() {
        // I4: previouslyEnabled was frozen entirely while the phase was NO_COMBAT, so as not to
        // wipe the resource memory on a target flicker. But the defensive axis DOES
        // decide in NO_COMBAT, and with its memory frozen its grace window never got to
        // start: threatened and with the obsidian coming and going -you place one, you pick up
        // another-, hole-filler went in and out once for every crossing of the minimum, with its
        // chat line every time.
        CombatDirector director = new CombatDirector();

        int changes = 0;
        Boolean previous = null;
        for (int tick = 0; tick < 80; tick++) {
            int obsidian = tick % 10 < 3 ? 1 : 0;
            CombatSnapshot alone = Snapshots.of(false, 0, 0, 0, false, false, false, 2,
                    Map.of(Resource.CRYSTALS, 12, Resource.OBSIDIAN, obsidian))
                .withDefense(10.0, 0.0, true, true);
            boolean up = enables(director.tick(alone, APPROACH), ManagedModules.HOLE_FILLER);

            assertEquals(CombatState.NO_COMBAT, director.state(), "precondition: there is no target");
            if (previous != null && up != previous) changes++;
            previous = up;
        }

        assertEquals(0, changes,
            "the resource grace window has to hold for the defensive axis too: "
                + "none of the obsidian gaps reaches the "
                + CombatDirector.RESOURCE_RELEASE_DWELL_TICKS + " ticks");
        assertTrue(previous, "and at the end it is still on");
    }

    // --- §5: the defensive posture ---

    @Test
    void aThreatenedPostureAddsItsModulesOnTopOfThePhase() {
        CombatSnapshot snapshot = surface().withDefense(14.0, 6.0, false, true);
        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SURFACE, plan.state(), "the offensive phase does not change");
        assertEquals(CombatPosture.THREATENED, plan.posture());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA), "the two axes add up, you do not pick one");
        assertTrue(enables(plan, ManagedModules.HOLE_FILLER));
        assertTrue(enables(plan, ManagedModules.ANTI_ANVIL));
        assertTrue(enables(plan, ManagedModules.ANTI_BED));
        assertTrue(enables(plan, ManagedModules.ANTI_ANCHOR));
    }

    @Test
    void aCalmPostureAddsNothing() {
        Plan plan = settle(new CombatDirector(), surface());
        assertEquals(CombatPosture.CALM, plan.posture());
        assertFalse(enables(plan, ManagedModules.HOLE_FILLER));
        assertFalse(enables(plan, ManagedModules.SURROUND));
    }

    @Test
    void surroundOnlyGoesUpThreatenedInAHoleAndOnTheGround() {
        CombatSnapshot inAHole = surface().withDefense(10.0, 0.0, true, true);
        assertTrue(enables(settle(new CombatDirector(), inAHole), ManagedModules.SURROUND));

        CombatSnapshot inTheAir = surface().withDefense(10.0, 0.0, true, false);
        assertFalse(enables(settle(new CombatDirector(), inTheAir), ManagedModules.SURROUND),
            "toggle-on-y-change and centerPlayer(): enabling it without standing on the ground turns itself off in a loop");

        CombatSnapshot outOfTheHole = surface().withDefense(10.0, 0.0, false, true);
        assertFalse(enables(settle(new CombatDirector(), outOfTheHole), ManagedModules.SURROUND),
            "it is a defensive hole module and that is its only place");
    }

    // --- §6: the hysteresis that really protects ---

    @Test
    void losingTheTargetForOneTickDoesNotEndTheFight() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        Plan plan = director.tick(noTarget(), APPROACH);

        assertEquals(CombatState.SURFACE, plan.state(),
            "nobody this tick and the fight is over are not the same");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA),
            "during the grace decisions are still made with the last thing seen of them");
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP));
    }

    @Test
    void losingTheTargetForTheWholeGraceDoesEndTheFight() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        Plan plan = null;
        for (int i = 0; i < CombatDirector.TARGET_GRACE_TICKS - 1; i++) {
            plan = director.tick(noTarget(), APPROACH);
        }
        assertEquals(CombatState.SURFACE, plan.state(), "still inside the grace");

        plan = director.tick(noTarget(), APPROACH);
        assertEquals(CombatState.NO_COMBAT, plan.state());
        assertTrue(plan.enable().isEmpty());
    }

    @Test
    void theTargetGraceRestartsWhenHeComesBack() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        // It flickers again and again without the whole grace running out: it never falls to NO_COMBAT.
        for (int i = 0; i < 100; i++) {
            director.tick(i % 5 == 0 ? surface() : noTarget(), APPROACH);
            assertEquals(CombatState.SURFACE, director.state(), "tick " + i);
        }
    }

    @Test
    void aTargetLeavingTheClassifyRangeAlsoGetsTheGrace() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        Plan plan = director.tick(surface().withTargetDistance(30.0), APPROACH);
        assertEquals(CombatState.SURFACE, plan.state(), "leaving range is losing the target, with its grace");

        for (int i = 0; i < CombatDirector.TARGET_GRACE_TICKS; i++) {
            plan = director.tick(surface().withTargetDistance(30.0), APPROACH);
        }
        assertEquals(CombatState.NO_COMBAT, plan.state());
    }

    @Test
    void theApproachBandDoesNotOscillateWithTheTargetSittingAtTheThreshold() {
        // §11: the distance band does not oscillate with the target right at approach.
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        for (int i = 0; i < 40; i++) {
            director.tick(surface().withTargetDistance(APPROACH), APPROACH);
            assertEquals(CombatState.SURFACE, director.state(), "tick " + i + ": right at the threshold it does not leave");
        }
    }

    @Test
    void enteringApproachNeedsToCrossTheUpperEdgeOfTheBand() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        settle(director, surface().withTargetDistance(APPROACH + CombatDirector.APPROACH_BAND));
        assertEquals(CombatState.SURFACE, director.state(), "at the upper edge not yet");

        settle(director, surface().withTargetDistance(APPROACH + CombatDirector.APPROACH_BAND + 0.1));
        assertEquals(CombatState.APPROACH, director.state());
    }

    @Test
    void leavingApproachNeedsToCrossTheLowerEdgeOfTheBand() {
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(9.0));
        assertEquals(CombatState.APPROACH, director.state());

        settle(director, surface().withTargetDistance(APPROACH));
        assertEquals(CombatState.APPROACH, director.state(),
            "inside the band, the phase you were already in rules");

        settle(director, surface().withTargetDistance(APPROACH - CombatDirector.APPROACH_BAND));
        assertEquals(CombatState.SURFACE, director.state());
    }

    @Test
    void theApproachBandIsCrossedWithoutWaitingAnyTicks() {
        // The hysteresis of these two phases is of distance, not of time: waiting for any dwell to be
        // fulfilled must not cost the first combo.
        CombatDirector director = new CombatDirector();
        settle(director, surface().withTargetDistance(9.0));

        director.tick(surface().withTargetDistance(2.0), APPROACH);
        assertEquals(CombatState.SURFACE, director.state(), "a single tick is enough");
    }

    @Test
    void aBlockSignalNeedsTwoTicksAndNotMore() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        director.tick(burrowed, APPROACH);
        assertEquals(CombatState.SURFACE, director.state(), "one tick of reading is not enough");

        director.tick(burrowed, APPROACH);
        assertEquals(CombatState.BURROWED, director.state(), "two ticks in a row are");
    }

    @Test
    void aFlickeringBlockSignalNeverChangesThePhase() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < 100; i++) director.tick(i % 2 == 0 ? burrowed : surface(), APPROACH);

        assertEquals(CombatState.SURFACE, director.state());
    }

    @Test
    void aTakeoffNeedsItsOwnLongerHold() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        for (int i = 0; i < CombatDirector.GLIDE_ENTER_HOLD_TICKS - 1; i++) director.tick(flying, APPROACH);
        assertEquals(CombatState.SURFACE, director.state(), "a take-off takes a couple of ticks to be real");

        director.tick(flying, APPROACH);
        assertEquals(CombatState.CHASE, director.state());
    }

    @Test
    void landingBouncesDoNotLeaveTheChaseEarly() {
        CombatDirector director = new CombatDirector();
        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        settle(director, flying);
        assertEquals(CombatState.CHASE, director.state());

        // Landing skims the ground: the gliding flag turns off and on several times.
        CombatSnapshot landed = with(surface(), false, false, false, false).withTargetDistance(8.0);
        for (int i = 0; i < 60; i++) {
            director.tick(i % 5 == 4 ? flying : landed, APPROACH);
            assertEquals(CombatState.CHASE, director.state(), "tick " + i + ": still bouncing");
        }

        for (int i = 0; i < CombatDirector.GLIDE_EXIT_HOLD_TICKS; i++) director.tick(landed, APPROACH);
        assertEquals(CombatState.APPROACH, director.state(), "ten ticks in a row on foot do leave");
    }

    @Test
    void divingIntoCrystalRangeLeavesTheChaseWithoutWaitingForTheLandingHold() {
        CombatDirector director = new CombatDirector();
        CombatSnapshot flying = with(surface(), false, false, true, false).withTargetDistance(8.0);
        settle(director, flying);
        assertEquals(CombatState.CHASE, director.state());

        // Still gliding, but they have come at you: that is a position signal, not a bounce.
        CombatSnapshot diving = with(surface(), false, false, true, false).withTargetDistance(2.0);
        for (int i = 0; i < CombatDirector.BLOCK_HOLD_TICKS; i++) director.tick(diving, APPROACH);

        assertEquals(CombatState.SURFACE, director.state());
    }

    @Test
    void engagingDoesNotWaitAtAll() {
        CombatDirector director = new CombatDirector();
        director.tick(surface(), APPROACH);

        assertEquals(CombatState.SURFACE, director.state(),
            "at the start of a fight reacting cannot take long");
    }

    @Test
    void aFreshPhaseIsAbandonedAsSoonAsTheNewOneHolds() {
        // MIN_DWELL_TICKS goes away (§6): it made the director take longer to correct its mistake
        // than to make it, and it must never delay a transition that turns the aura back on.
        CombatDirector director = new CombatDirector();
        settle(director, surface());

        CombatSnapshot burrowed = with(surface(), false, true, false, false);
        for (int i = 0; i < CombatDirector.BLOCK_HOLD_TICKS; i++) director.tick(burrowed, APPROACH);
        assertEquals(CombatState.BURROWED, director.state());

        CombatSnapshot surrounded = with(surface(), true, false, false, false);
        for (int i = 0; i < CombatDirector.BLOCK_HOLD_TICKS; i++) director.tick(surrounded, APPROACH);
        assertEquals(CombatState.SURROUNDED, director.state(), "just entered or not, the correction does not wait");
    }

    // --- §7: the aura stays out of the resource filter ---

    @Test
    void withoutCrystalsTheAuraStillGoesUpAndIsReportedAsAWarning() {
        // §11: with 0 crystals, crystal-aura stays in the enable list. Turning it off takes away your
        // autobreak, which is exactly what keeps you alive when you have nothing to answer with.
        Map<Resource, Integer> noCrystals = Map.of(
            Resource.OBSIDIAN, 64, Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, noCrystals);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertFalse(skips(plan, ManagedModules.CRYSTAL_AURA), "it is not an omission");
        assertTrue(plan.warnings().contains(Msg.of(PvpText.AURA_NO_CRYSTALS)), "it is warned about");
        assertEquals(CombatState.SURFACE, plan.state(), "and OUT_OF_RESOURCES is not reported because of it");
    }

    @Test
    void withCrystalsThereIsNoWarning() {
        assertTrue(settle(new CombatDirector(), surface()).warnings().isEmpty());
    }

    @Test
    void withoutTotemsButWithAntiSuicideTheAuraStillGoesUp() {
        // The totem floor was a life decision taken with an item counter, and Meteor already
        // takes it with the exact damage: anti-suicide refuses to place or break a crystal that would
        // kill you. Without totems is exactly when the autobreak is needed most.
        CombatSnapshot noTotems = Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, FULL);
        Plan plan = settle(new CombatDirector(), noTotems);

        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertFalse(skips(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "and the rest still comes up");
    }

    @Test
    void withoutTotemsAndWithAntiSuicideOffTheAuraIsRefused() {
        // anti-suicide is only a default value: off, that protection does not exist and the totem
        // floor is once again the only thing left.
        CombatSnapshot noTotems =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, FULL));
        Plan plan = settle(new CombatDirector(), noTotems);

        assertFalse(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(skips(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(plan.skipped().stream().anyMatch(sk -> sk.reason().equals(Msg.of(PvpText.TOTEM_FLOOR))),
            "and the reason says why, not only that totems are missing");
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "and the rest still comes up");
    }

    @Test
    void withTotemsTheAntiSuicideSettingChangesNothing() {
        CombatSnapshot withTotems =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, FULL));
        assertTrue(enables(settle(new CombatDirector(), withTotems), ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void withoutTotemsAndOnlyCrystalsItIsOutOfResourcesOnlyWithAntiSuicideOff() {
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 0,
            Map.of(Resource.CRYSTALS, 12));
        assertEquals(CombatState.SURFACE, settle(new CombatDirector(), snapshot).state(),
            "with anti-suicide on the aura comes up and there is something to fight with");
        assertEquals(CombatState.OUT_OF_RESOURCES,
            settle(new CombatDirector(), Snapshots.antiSuicideOff(snapshot)).state());
    }

    @Test
    void withoutAPickaxeAutoCitySkipsItInSurrounded() {
        Map<Resource, Integer> noPickaxe = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.WEBS, 5, Resource.ANVILS, 3);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, SURROUNDED_SIDES, 3.0, false, false, false, 2, noPickaxe);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertEquals(CombatState.SURROUNDED, plan.state());
        assertFalse(enables(plan, ManagedModules.AUTO_CITY));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(skips(plan, ManagedModules.AUTO_CITY));
    }

    @Test
    void withNothingAtAllAndNoTotemsItReportsOutOfResources() {
        // With nothing on you and without the anti-suicide net, not even the autobreak is left.
        CombatSnapshot broke =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, Map.of()));
        Plan plan = settle(new CombatDirector(), broke);

        assertEquals(CombatState.OUT_OF_RESOURCES, plan.state());
        assertTrue(plan.enable().isEmpty());
        assertFalse(plan.skipped().isEmpty(), "it has to say what it was missing");
    }

    @Test
    void withNothingAtAllButWithAntiSuicideTheAutobreakIsStillSomethingToFightWith() {
        CombatSnapshot broke = Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, Map.of());
        Plan plan = settle(new CombatDirector(), broke);

        assertEquals(CombatState.SURFACE, plan.state());
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(plan.warnings().contains(Msg.of(PvpText.AURA_NO_CRYSTALS)));
    }

    @Test
    void outOfResourcesIsHowItReportsNotWhereItLives() {
        CombatSnapshot broke =
            Snapshots.antiSuicideOff(Snapshots.of(true, 3.0, 0, 0, false, false, false, 0, Map.of()));
        CombatDirector director = new CombatDirector();
        settle(director, broke);

        assertEquals(CombatState.SURFACE, director.state(), "the physical phase is still SURFACE");
    }

    @Test
    void aPhaseThatAsksForNothingIsNotOutOfResources() {
        CombatSnapshot broke = Snapshots.of(true, 8.0, 0, 0, false, false, false, 0, Map.of());
        assertEquals(CombatState.APPROACH, settle(new CombatDirector(), broke).state(),
            "there being nothing to enable is not running out of resources");
    }

    @Test
    void notEnoughObsidianForATrapSkipsItEvenWithSomeObsidian() {
        Map<Resource, Integer> little = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 2, Resource.WEBS, 5);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, little);

        Plan plan = settle(new CombatDirector(), snapshot);

        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
    }

    @Test
    void withoutWebsTheWebIsSkippedWhereItWouldHaveGoneUp() {
        Map<Resource, Integer> noWebs = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        CombatSnapshot snapshot = Snapshots.of(true, 4.0, 0, 0, false, false, false, 2, noWebs)
            .withTargetId("enemy");

        CombatDirector director = new CombatDirector();
        Plan plan = pullingAwayTo(director, snapshot, 3.5);

        assertEquals(CombatState.SURFACE, plan.state());
        assertTrue(director.targetRetreating(), "precondition: the auto-web gate is open");
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertFalse(enables(plan, ManagedModules.AUTO_WEB));
        assertTrue(skips(plan, ManagedModules.AUTO_WEB));
    }

    // --- spec §6.2: the resource hysteresis, which is not to be touched ---

    /** SURFACE with auto-trap's obsidian at a specific value, the rest of the gear complete. */
    private static CombatSnapshot withObsidian(int amount) {
        Map<Resource, Integer> resources = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, amount,
            Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        return Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, resources);
    }

    @Test
    void obsidianBelowMinimumBrieflyKeepsAutoTrapEnabledIfItWasOnBefore() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP), "precondition: it was already on");

        Plan plan = director.tick(withObsidian(2), APPROACH);
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "one tick below the minimum does not release it");
    }

    @Test
    void obsidianBelowMinimumDoesNotEnableAutoTrapIfItWasNeverOn() {
        Plan plan = settle(new CombatDirector(), withObsidian(7));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP), "without history it requires the full minimum");
    }

    @Test
    void sustainedShortageDoesNotDropAutoTrapBeforeTheReleaseDwellWindow() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS - 1; i++) {
            plan = director.tick(withObsidian(0), APPROACH);
        }
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP),
            "it has not yet been " + CombatDirector.RESOURCE_RELEASE_DWELL_TICKS + " ticks in a row below the minimum");
    }

    @Test
    void sustainedShortageDropsAutoTrapAfterTheReleaseDwellWindow() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS; i++) {
            plan = director.tick(withObsidian(0), APPROACH);
        }
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP),
            "once the " + CombatDirector.RESOURCE_RELEASE_DWELL_TICKS + " ticks below the minimum are up, it is released");
    }

    @Test
    void theResourceReleaseDwellIsStillTwentyTicks() {
        assertEquals(20, CombatDirector.RESOURCE_RELEASE_DWELL_TICKS,
            "§9: checked as correct, not to be touched");
    }

    @Test
    void aModuleWithAMinimumOfOneAlsoGetsTheReleaseDwellWindow() {
        // With minimum() == 1, "half" rounded to the same minimum and gave no grace at all.
        // auto-anvil (minimum 1) is one of the modules this affected: against someone burrowed
        // at 3 blocks it is asked for, and the phase depends on nothing that moves.
        CombatDirector director = new CombatDirector();
        CombatSnapshot burrowed = with(surface(), false, true, false, false).withTargetDistance(3.0);
        Plan settled = settle(director, burrowed);
        assertTrue(enables(settled, ManagedModules.AUTO_ANVIL), "precondition: it was already on");

        Map<Resource, Integer> noAnvils = Map.of(
            Resource.CRYSTALS, 12, Resource.OBSIDIAN, 64, Resource.WEBS, 5, Resource.PICKAXE, 1);
        CombatSnapshot noWebsSnapshot = new CombatSnapshot(true, 3.0, 0, 0, true, false, false, 2,
            noAnvils, "enemy", 0, CombatSnapshot.FULL_HEALTH, 0, false, false, false, true);

        Plan plan = null;
        for (int i = 0; i < CombatDirector.RESOURCE_RELEASE_DWELL_TICKS - 1; i++) {
            plan = director.tick(noWebsSnapshot, APPROACH);
        }
        assertTrue(enables(plan, ManagedModules.AUTO_ANVIL), "still inside the grace window");

        plan = director.tick(noWebsSnapshot, APPROACH);
        assertFalse(enables(plan, ManagedModules.AUTO_ANVIL), "once the window is up, it is released even though the minimum is 1");
    }

    @Test
    void obsidianOscillatingAroundTheMinimumDoesNotFlickerAutoTrap() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        for (int i = 0; i < 20; i++) {
            Plan plan = director.tick(withObsidian(i % 2 == 0 ? 6 : 10), APPROACH);
            assertTrue(enables(plan, ManagedModules.AUTO_TRAP), "tick " + i + ": it must not flicker");
        }
    }

    @Test
    void aOneTickBlipWithoutATargetDoesNotEraseTheResourceMemory() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        for (int i = 0; i < 5; i++) director.tick(withObsidian(2), APPROACH);
        assertEquals(CombatState.SURFACE, director.state());

        director.tick(CombatSnapshot.none(), APPROACH);

        Plan plan = director.tick(withObsidian(2), APPROACH);
        assertEquals(CombatState.SURFACE, director.state());
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP),
            "the resource memory should have survived the blip, not required the full minimum all at once");
    }

    // --- design §1: the allowed set a profile restricts the director to ---

    private static Set<ManagedModule> allExcept(ManagedModule... excluded) {
        Set<ManagedModule> allowed = new LinkedHashSet<>(ManagedModules.ALL);
        allowed.removeAll(List.of(excluded));
        return Set.copyOf(allowed);
    }

    private static Plan settle(CombatDirector director, CombatSnapshot snapshot, Set<ManagedModule> allowed) {
        Plan plan = null;
        for (int i = 0; i < CombatDirector.GLIDE_EXIT_HOLD_TICKS + 5; i++) {
            plan = director.tick(snapshot, APPROACH, DefensivePolicy.THREAT_MARGIN, allowed);
        }
        return plan;
    }

    @Test
    void aDisallowedModuleIsSkippedWithProfileOffAndNeverEnabled() {
        Plan plan = settle(new CombatDirector(), surface(), allExcept(ManagedModules.AUTO_TRAP));

        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
        assertTrue(skips(plan, ManagedModules.AUTO_TRAP));
        Msg reason = plan.skipped().stream()
            .filter(skipped -> skipped.module().equals(ManagedModules.AUTO_TRAP))
            .map(Skipped::reason).findFirst().orElse(null);
        assertEquals(Msg.of(PvpText.PROFILE_OFF), reason);
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA), "what the profile does allow still comes up");
    }

    @Test
    void withEveryOffensiveModuleDisallowedTheReportedStateIsThePhysicalOneNotOutOfResources() {
        // SURFACE asks for crystal-aura and auto-trap (§4.2): disallow both and there was never
        // anything the profile would have let go up. Design §1: OUT_OF_RESOURCES is measured only
        // on offensive ∩ allowed, so an empty intersection must not raise the loud alarm -a
        // defensive profile that disallows every offensive module must not shout it on every fight.
        Set<ManagedModule> allowed = allExcept(ManagedModules.CRYSTAL_AURA, ManagedModules.AUTO_TRAP);
        Plan plan = settle(new CombatDirector(), surface(), allowed);

        assertEquals(CombatState.SURFACE, plan.state());
        assertTrue(plan.enable().isEmpty());
    }

    @Test
    void oneAllowedWithoutResourcesAndOneDisallowedStillReportsOutOfResources() {
        // Mixed case: crystal-aura disallowed (a profile choice the spec itself allows, §1) and
        // auto-trap allowed but broke. offensiveAllowed is {auto-trap} alone, and nothing in it
        // went up, so it is still OUT_OF_RESOURCES: being choosy is not the same as being broke,
        // but here both happen at once and the shortage must still be reported.
        Map<Resource, Integer> noObsidian = Map.of(
            Resource.CRYSTALS, 12, Resource.WEBS, 5, Resource.ANVILS, 3, Resource.PICKAXE, 1);
        CombatSnapshot snapshot = Snapshots.of(true, 3.0, 0, 0, false, false, false, 2, noObsidian)
            .withTargetId("enemy");
        Set<ManagedModule> allowed = allExcept(ManagedModules.CRYSTAL_AURA);

        Plan plan = settle(new CombatDirector(), snapshot, allowed);

        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));
        assertTrue(skips(plan, ManagedModules.CRYSTAL_AURA), "disallowed, not merely skipped for a shortage");
        assertEquals(CombatState.OUT_OF_RESOURCES, plan.state());
    }

    @Test
    void aDisallowedAutoTrapNeverReservesTheObsidianSurroundAndHoleFillerNeed() {
        // The I3 share-out with auto-trap disallowed: the filter has to run before hasEnough can
        // claim anything on its behalf, so with only 5 obsidian hole-filler (1) and surround (4)
        // still both fit exactly as they do in the existing eight-obsidian share-out test -
        // disallowing auto-trap changes nothing about what the still-allowed ones get.
        Set<ManagedModule> allowed = allExcept(ManagedModules.AUTO_TRAP);
        Plan plan = settle(new CombatDirector(), inAHoleWithObsidian(5), allowed);

        assertTrue(enables(plan, ManagedModules.HOLE_FILLER));
        assertTrue(enables(plan, ManagedModules.SURROUND));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP));

        Msg reason = plan.skipped().stream()
            .filter(skipped -> skipped.module().equals(ManagedModules.AUTO_TRAP))
            .map(Skipped::reason).findFirst().orElse(null);
        assertEquals(Msg.of(PvpText.PROFILE_OFF), reason,
            "filtered before it could ever reserve part of the shared stack, not skipped for a shortage");
    }

    @Test
    void theShortOverloadsStillAllowEveryManagedModule() {
        // The two- and three-argument overloads must keep behaving exactly as before design §1:
        // they pass every managed module as allowed, so none of the existing tests above them move.
        Plan plan = settle(new CombatDirector(), surface());

        assertFalse(skips(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.CRYSTAL_AURA));
        assertTrue(enables(plan, ManagedModules.AUTO_TRAP));
    }

    // --- reset ---

    @Test
    void resetForgetsTheStateAndTheCounters() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());
        director.reset();

        assertEquals(CombatState.NO_COMBAT, director.state());
        assertEquals(0, director.ticksInState());
        assertFalse(director.targetRetreating());
    }

    @Test
    void resetForgetsThePreviouslyEnabledModules() {
        CombatDirector director = new CombatDirector();
        Plan settled = settle(director, withObsidian(64));
        assertTrue(enables(settled, ManagedModules.AUTO_TRAP));

        director.reset();

        Plan plan = settle(director, withObsidian(7));
        assertFalse(enables(plan, ManagedModules.AUTO_TRAP), "reset() forgets what was on");
    }

    @Test
    void resetForgetsTheTargetGrace() {
        CombatDirector director = new CombatDirector();
        settle(director, surface());
        director.reset();

        Plan plan = director.tick(CombatSnapshot.none(), APPROACH);
        assertEquals(CombatState.NO_COMBAT, plan.state(), "without a memory of the target there is no grace to give");
        assertTrue(plan.enable().isEmpty());
    }
}
