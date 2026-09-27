package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import com.xploits.pvp.crystal.core.Decision.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
import static com.xploits.pvp.crystal.core.Crystals.boxed;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.hands;
import static com.xploits.pvp.crystal.core.Crystals.outOfBreakRange;
import static com.xploits.pvp.crystal.core.Crystals.outOfRange;
import static com.xploits.pvp.crystal.core.Crystals.player;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.weakened;
import static com.xploits.pvp.crystal.core.Crystals.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Meteor parity: one test per row of the spec's P1 table and per Q1 gap, with the line numbers of
 * {@code CrystalAura.java} in the 1.21.11 sources. The budget is off here ({@link Crystals#METEOR}), so
 * what is left is Meteor's own rules; {@code CrystalBrainBudgetTest} adds the budget on top.
 */
class CrystalBrainParityTest {
    private static final CrystalSettings NO_ROTATE = METEOR.toBuilder().rotate(false).build();
    private static final int NO = CrystalTick.Hands.NO_EFFECT;

    static Action only(List<Action> actions) {
        assertEquals(1, actions.size(), actions.toString());
        return actions.get(0);
    }

    static void assertBreaks(int id, List<Action> actions) {
        Action a = only(actions);
        assertEquals(Kind.BREAK, a.decision().kind(), actions.toString());
        assertEquals(id, a.decision().ref());
    }

    static void assertPlaces(long pos, List<Action> actions) {
        Action a = only(actions);
        assertEquals(Kind.PLACE, a.decision().kind(), actions.toString());
        assertEquals(pos, a.decision().ref());
    }

    static void assertNothing(List<Action> actions) {
        assertEquals(List.of(), actions);
    }

    private static List<Action> once(CrystalSettings s, Crystals.Tick t) {
        return new CrystalBrain().preTick(s, t.build());
    }

    @Test
    void theDefaultsAreMeteors() {
        CrystalSettings d = CrystalSettings.defaults();

        assertEquals(10.0, d.targetRange(), 0.0);
        assertEquals(6.0, d.minDamage(), 0.0);
        assertEquals(6.0, d.maxDamage(), 0.0);
        assertTrue(d.antiSuicide());
        assertTrue(d.rotate());
        assertEquals(AutoSwitch.NORMAL, d.autoSwitch());
        assertTrue(d.noGapSwitch());
        assertTrue(d.noBowSwitch());
        assertTrue(d.antiWeakness());
        assertTrue(d.place());
        assertTrue(d.facePlace());
        assertEquals(8.0, d.facePlaceHealth(), 0.0);
        assertEquals(2.0, d.facePlaceDurability(), 0.0);
        assertTrue(d.breakCrystals());
        assertEquals(2, d.breakAttempts());
        assertEquals(25, d.attackFrequency());
        assertTrue(d.fastBreak());
        assertEquals(PauseMode.PLACE, d.pauseOnUse());
        assertEquals(PauseMode.NONE, d.pauseOnMine());
        assertTrue(d.pauseOnLag());
        assertEquals(5.0, d.pauseHealth(), 0.0);
        assertTrue(d.selfBudget());
        assertEquals(5.0, d.reserve(), 0.0);
        assertEquals(0.5, d.safeSelfDamage(), 0.0);
        assertEquals(20, CrystalBrain.ATTACK_WINDOW_LAST_TICK);
        assertEquals(1.5, CrystalBrain.FACE_PLACE_MIN_DAMAGE, 0.0);
        assertEquals(10, CrystalBrain.LAST_ROTATION_STOP_DELAY);
        assertEquals(1, CrystalBrain.ANTI_WEAKNESS_SWITCH_TICKS);
    }

    // P1: Targets

    @Test
    void targetsArePlayersWithinTargetRangeNotCreativeDeadOrFriends() {
        // Lines 1222-1253: creative, dead and friends are left out; beyond 10 too (squared distance > 100).
        TargetView edge = player("edge", 10, 20);
        TargetView far = player("far", 10.25, 20);
        TargetView creative = new TargetView("creative", 9, 20, TargetView.NO_ARMOR, true, true, false);
        TargetView dead = new TargetView("dead", 9, 20, TargetView.NO_ARMOR, false, false, false);
        TargetView friend = new TargetView("friend", 9, 20, TargetView.NO_ARMOR, false, true, true);
        Candidate forOthers = spot(1, Map.of("far", 50.0, "creative", 50.0, "dead", 50.0, "friend", 50.0), 1);
        Candidate forEdge = spot(2, Map.of("edge", 6.0), 1);
        CrystalBrain b = new CrystalBrain();

        List<Action> actions = b.preTick(METEOR, tick(1).targets(edge, far, creative, dead, friend)
            .candidates(forOthers, forEdge).build());

        assertEquals(List.of("edge"), b.targets());
        assertPlaces(2, actions);
    }

    @Test
    void aPlayerAnUlpPastTheRangeIsNoTarget() {
        // Parity bug fixed in R2-4: the core compared the distance (a square root) with the range, and
        // sqrt(nextUp(100)) rounds to exactly 10.0, so a player Meteor leaves out (line 1250 compares the
        // squares) was a target. The adapter now hands over the squared distance, as Meteor reads it.
        double squared = Math.nextUp(100.0);
        assertEquals(10.0, Math.sqrt(squared), 0.0);
        TargetView edge = ServerValues.target(ENEMY, squared, 20, 0, TargetView.NO_ARMOR, false, true, false).orElseThrow();
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(METEOR, tick(1).targets(edge).candidates(spot(1, 6, 1)).build()));
        assertEquals(List.of(), b.targets());
        assertTrue(Reach.inTargetRange(100, 10));
        assertFalse(Reach.inTargetRange(squared, 10));
    }

    @Test
    void withNoTargetNothingIsDone() {
        // Lines 716-719.
        CrystalBrain b = new CrystalBrain();

        assertNothing(b.preTick(METEOR, tick(1).targets(player(ENEMY, 10.25, 20))
            .crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1)).build()));
        assertEquals(Decision.none(Reason.NO_TARGETS), b.lastDecision());
        assertEquals(List.of(), b.targets());
    }

    // P1: Target damage

    @Test
    void theDamageIsSummedOverEveryTarget() {
        // Lines 1198-1210: 3 + 3 reaches min-damage 6; 5.75 to one target alone does not.
        TargetView a = player("a", 3, 20);
        TargetView c = player("c", 4, 20);
        Candidate both = spot(1, Map.of("a", 3.0, "c", 3.0), 1);
        Candidate one = spot(2, Map.of("a", 5.75), 1);

        assertPlaces(1, once(METEOR, tick(1).targets(a, c).candidates(one, both)));
        assertBreaks(7, once(METEOR, tick(1).targets(a, c).crystals(crystal(7, 1007, Map.of("a", 3.0, "c", 3.0), 1))));
        assertNothing(once(METEOR, tick(1).targets(a, c).candidates(one)));
    }

    @Test
    void theSumIsTakenInFloatAsMeteorTakesIt() {
        // 3 + nextDown(3) is under 6 in double and exactly 6.0 in float: Meteor places, and so does ++.
        float x = 3.0f;
        float y = Math.nextDown(3.0f);
        Candidate s = spot(1, Map.of("a", (double) x, "c", (double) y), 1);

        assertPlaces(1, once(METEOR, tick(1).targets(player("a", 3, 20), player("c", 4, 20)).candidates(s)));
    }

    // P1: Candidate

    @Test
    void aSpotMustBeInRangeWithNothingInItsBox() {
        // Lines 934-969: in range, and no entity but spectators in the 1x2x1 box above the base. The
        // crystal in one box deals nothing, so Meteor would not break it and it does not stop placing.
        CrystalSeen idle = crystal(5, 0, 0);
        Candidate far = outOfRange(spot(1, 20, 1));
        Candidate someone = boxed(spot(2, 18, 1), Set.of(), true);
        Candidate onACrystal = boxed(spot(3, 16, 1), Set.of(5), false);
        Candidate free = spot(4, 8, 1);

        assertPlaces(4, once(METEOR, tick(1).crystals(idle).candidates(far, someone, onACrystal, free)));
    }

    @Test
    void theHighestDamageWinsAndEqualOnesGoToTheFirstFound() {
        // Lines 781, 972: strictly greater replaces, so the first of equal ones stays.
        assertPlaces(2, once(METEOR, tick(1).candidates(spot(1, 7, 1), spot(2, 8, 1), spot(3, 8, 1))));
        assertBreaks(2, once(METEOR, tick(1).crystals(crystal(1, 7, 1), crystal(2, 8, 1), crystal(3, 8, 1))));
    }

    // P1: Damage filters

    @Test
    void selfDamageAndMinDamageDecideWhereToPlace() {
        // Lines 953, 959-961: self <= max-damage 6, self < health (anti-suicide), damage >= min-damage 6.
        CrystalSettings noAntiSuicide = METEOR.toBuilder().antiSuicide(false).build();

        assertPlaces(2, once(METEOR, tick(1).candidates(spot(1, 20, 6.25), spot(2, 10, 6))));
        assertPlaces(2, once(METEOR, tick(1).health(6).candidates(spot(1, 20, 6), spot(2, 10, 5.75))));
        assertPlaces(1, once(noAntiSuicide, tick(1).health(6).candidates(spot(1, 20, 6), spot(2, 10, 5.75))));
        assertNothing(once(METEOR, tick(1).candidates(spot(1, 5.75, 1))));
        assertPlaces(1, once(METEOR, tick(1).candidates(spot(1, 6, 1))));
    }

    @Test
    void selfDamageMinDamageAndRangeDecideWhatToBreak() {
        // Lines 807-819.
        CrystalSettings noAntiSuicide = METEOR.toBuilder().antiSuicide(false).build();

        assertBreaks(2, once(METEOR, tick(1).crystals(crystal(1, 20, 6.25), crystal(2, 10, 6))));
        assertBreaks(2, once(METEOR, tick(1).health(6).crystals(crystal(1, 20, 6), crystal(2, 10, 5.75))));
        assertBreaks(1, once(noAntiSuicide, tick(1).health(6).crystals(crystal(1, 20, 6), crystal(2, 10, 5.75))));
        assertNothing(once(METEOR, tick(1).crystals(crystal(1, 5.75, 1))));
        assertBreaks(1, once(METEOR, tick(1).crystals(crystal(1, 6, 1))));
        assertNothing(once(METEOR, tick(1).crystals(outOfBreakRange(crystal(1, 20, 1)))));
    }

    // P1: Face-place

    @Test
    void faceplacingLowersMinDamageTo1Point5() {
        // Lines 1127-1149, 817, 959: any target at <= 8 health, or wearing a piece at <= 2 %.
        TargetView low = player(ENEMY, 3, 8);
        TargetView notLow = player(ENEMY, 3, 8.25);
        TargetView worn = new TargetView(ENEMY, 9, 20, 2, false, true, false);
        TargetView notWorn = new TargetView(ENEMY, 9, 20, 2.25, false, true, false);
        CrystalSettings off = METEOR.toBuilder().facePlace(false).build();

        assertPlaces(1, once(METEOR, tick(1).targets(low).candidates(spot(1, 1.5, 1))));
        assertNothing(once(METEOR, tick(1).targets(low).candidates(spot(1, 1.25, 1))));
        assertNothing(once(METEOR, tick(1).targets(notLow).candidates(spot(1, 1.5, 1))));
        assertPlaces(1, once(METEOR, tick(1).targets(worn).candidates(spot(1, 1.5, 1))));
        assertNothing(once(METEOR, tick(1).targets(notWorn).candidates(spot(1, 1.5, 1))));
        assertNothing(once(off, tick(1).targets(low).candidates(spot(1, 1.5, 1))));
        assertBreaks(1, once(METEOR, tick(1).targets(low).crystals(crystal(1, 1.5, 1))));
        // Any target: one at full health does not stop another from being face-placed.
        assertPlaces(1, once(METEOR, tick(1).targets(player("full", 3, 20), low).candidates(spot(1, 1.5, 1))));
    }

    // P1: Break attempts and the wait after an attack

    @Test
    void aCrystalGetsThreeHitsWithMeteorsWaitBetweenThem() {
        // Line 801: skipped once attempts > break-attempts 2. Lines 697-708: after a hit it is left out
        // until the 5th pre-tick after it.
        CrystalBrain b = new CrystalBrain();
        CrystalSeen c = crystal(1, 8, 1);
        List<Long> hits = new ArrayList<>();

        for (long t = 1; t <= 20; t++) {
            List<Action> a = b.preTick(METEOR, tick(t).crystals(c).build());
            if (!a.isEmpty()) {
                assertBreaks(1, a);
                b.attackSent();
                hits.add(t);
            }
        }

        assertEquals(List.of(1L, 6L, 11L), hits);
        // Out of attempts it no longer stops placing either: Meteor piles here, and ++ keeps its rule.
        assertPlaces(9, b.preTick(METEOR, tick(21).crystals(c).candidates(spot(9, 8, 1)).build()));
    }

    // P1: Attack window

    @Test
    void atMost25AttacksIn21PreTicks() {
        // Lines 674-678, 771, 740: ticksPassed counts to 20 and the 21st pre-tick resets the attacks.
        CrystalBrain b = new CrystalBrain();
        List<CrystalSeen> all = new ArrayList<>(List.of(crystal(1, 8, 1)));
        assertBreaks(1, b.preTick(NO_ROTATE, tick(1).crystals(all).build()));
        b.attackSent();
        for (int id = 2; id <= 26; id++) {
            CrystalSeen c = crystal(id, 8, 1);
            all.add(c);
            Optional<Action> fast = b.crystalAdded(c, 20, HANDS);
            if (id <= 25) {
                assertEquals(Kind.BREAK, fast.orElseThrow().decision().kind(), "attack " + id);
                b.attackSent();
            } else {
                assertTrue(fast.isEmpty(), "the 26th attack");
            }
        }

        for (long t = 2; t <= 20; t++) assertNothing(b.preTick(NO_ROTATE, tick(t).crystals(all).build()));
        assertBreaks(1, b.preTick(NO_ROTATE, tick(21).crystals(all).build()));
    }

    // P1: Fast-break

    @Test
    void fastBreakNeedsMoreThanMinDamageNeverTheFacePlaceMinimum() {
        // Lines 740-743: damage > min-damage 6, even while face-placing.
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(METEOR, tick(1).targets(player(ENEMY, 3, 8)).build()));

        assertTrue(b.crystalAdded(crystal(1, 1.5, 1), 20, HANDS).isEmpty());
        assertTrue(b.crystalAdded(crystal(2, 6, 1), 20, HANDS).isEmpty());
        Action a = b.crystalAdded(crystal(3, 6.25, 1), 20, HANDS).orElseThrow();
        assertEquals(Decision.breakCrystal(3, Reason.BUDGET_OFF), a.decision());
        assertEquals(a.decision(), b.lastDecision());
    }

    @Test
    void fastBreakChecksNoPauseNoTimerAndNotTheBreakSetting() {
        // Line 740 checks only fast-break, this tick's rotation and the attack count.
        CrystalSettings s = METEOR.toBuilder().breakCrystals(false).place(false)
            .pauseOnUse(PauseMode.BOTH).pauseOnMine(PauseMode.BOTH).build();
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(s, tick(1).health(4).usingItem().mining().lagging().pauseModule()
            .crystals(crystal(1, 8, 1)).build()));

        assertEquals(Kind.BREAK, b.crystalAdded(crystal(2, 8, 1), 4, HANDS).orElseThrow().decision().kind());

        CrystalBrain off = new CrystalBrain();
        off.preTick(METEOR.toBuilder().fastBreak(false).build(), tick(1).build());
        assertTrue(off.crystalAdded(crystal(2, 8, 1), 20, HANDS).isEmpty());
    }

    @Test
    void fastBreakKeepsMeteorsOtherChecks() {
        // getBreakDamage (lines 791-822) with the health of that moment: anti-suicide, max-damage, range.
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).build());

        assertTrue(b.crystalAdded(crystal(1, 8, 4), 4, HANDS).isEmpty());
        assertTrue(b.crystalAdded(crystal(2, 8, 6.25), 20, HANDS).isEmpty());
        assertTrue(b.crystalAdded(outOfBreakRange(crystal(3, 8, 1)), 20, HANDS).isEmpty());
        assertTrue(b.crystalAdded(crystal(4, 8, 4), 4.25, HANDS).isPresent());
    }

    @Test
    void fastBreakReadsTheSettingsOfTheMomentTheCrystalArrives() {
        // Parity bug fixed in R2-4: Meteor reads fast-break, attack-frequency, min-damage and the other
        // settings live in onEntityAdded (lines 740-742, 791-822); the brain used the last pre-tick's.
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).build());
        assertTrue(b.crystalAdded(METEOR.toBuilder().fastBreak(false).build(), crystal(1, 8, 1), 20, HANDS).isEmpty());
        assertTrue(b.crystalAdded(METEOR.toBuilder().minDamage(8).build(), crystal(2, 8, 1), 20, HANDS).isEmpty());
        assertTrue(b.crystalAdded(METEOR, crystal(3, 8, 1), 20, HANDS).isPresent());
    }

    @Test
    void fastBreakMeasuresAgainstThePreviousPreTicksTargets() {
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).targets(player("a", 3, 20)).build());

        assertTrue(b.crystalAdded(crystal(1, 1001, Map.of("b", 10.0), 1), 20, HANDS).isEmpty());
        assertTrue(b.crystalAdded(crystal(2, 1002, Map.of("a", 10.0), 1), 20, HANDS).isPresent());
    }

    @Test
    void fastBreakWaitsForTheNextPreTickAfterARotation() {
        // Line 740: !didRotateThisTick. With rotate on, a break or a placement is the tick's one rotation,
        // and so is a fast-break until the next pre-tick.
        CrystalBrain b = new CrystalBrain();
        assertBreaks(1, b.preTick(METEOR, tick(1).crystals(crystal(1, 8, 1)).build()));
        assertTrue(b.crystalAdded(crystal(2, 8, 1), 20, HANDS).isEmpty());

        CrystalBrain fast = new CrystalBrain();
        assertNothing(fast.preTick(METEOR, tick(1).build()));
        assertTrue(fast.crystalAdded(crystal(1, 8, 1), 20, HANDS).isPresent());
        assertTrue(fast.crystalAdded(crystal(2, 8, 1), 20, HANDS).isEmpty());
        assertBreaks(2, fast.preTick(METEOR, tick(2).crystals(crystal(1, 8, 1), crystal(2, 8, 1)).build()));

        CrystalBrain noRotate = new CrystalBrain();
        assertBreaks(1, noRotate.preTick(NO_ROTATE, tick(1).crystals(crystal(1, 8, 1)).build()));
        assertTrue(noRotate.crystalAdded(crystal(2, 8, 1), 20, HANDS).isPresent());
        assertTrue(noRotate.crystalAdded(crystal(3, 8, 1), 20, HANDS).isPresent());
    }

    @Test
    void aFastBreakWaitsLikeAnyAttackCountedFromThePreTickBeforeIt() {
        // waitingToExplode starts at 0 on the attack between pre-ticks 1 and 2 and releases it on pre-tick 6.
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).build());
        CrystalSeen c = crystal(1, 8, 1);
        assertTrue(b.crystalAdded(c, 20, HANDS).isPresent());
        b.attackSent();

        for (long t = 2; t <= 5; t++) assertNothing(b.preTick(METEOR, tick(t).crystals(c).build()));
        assertBreaks(1, b.preTick(METEOR, tick(6).crystals(c).build()));
    }

    // P1: One at a time

    @Test
    void noPlacingWhileACrystalMeteorWouldBreakStands() {
        // Lines 921-924. Breaking is paused (pause-on-use Break while eating), so the crystal stays, and
        // it still stops the placement. One Meteor would not break (out of break range) does not.
        CrystalSettings s = METEOR.toBuilder().pauseOnUse(PauseMode.BREAK).build();

        assertNothing(once(s, tick(1).usingItem().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
        assertPlaces(9, once(s, tick(1).usingItem().crystals(outOfBreakRange(crystal(1, 8, 1))).candidates(spot(9, 8, 1))));
    }

    // P1: Rotate

    @Test
    void withRotateOneActionPerTickWithoutItBreakThenPlace() {
        // Lines 717-718, 852, 998.
        List<Action> rotating = once(METEOR, tick(1).crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1)));
        List<Action> not = once(NO_ROTATE, tick(1).crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1)));

        assertBreaks(1, rotating);
        assertEquals(2, not.size());
        assertEquals(Decision.breakCrystal(1, Reason.BUDGET_OFF), not.get(0).decision());
        assertEquals(Decision.place(9, Reason.BUDGET_OFF), not.get(1).decision());
    }

    @Test
    void theLastRotationIsHeldForTenPreTicks() {
        // Lines 636, 656-658, 722-727.
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).build());
        assertFalse(b.holdLastRotation());
        assertBreaks(1, b.preTick(METEOR, tick(2).crystals(crystal(1, 8, 1)).build()));
        assertFalse(b.holdLastRotation());

        for (long t = 3; t <= 11; t++) {
            b.preTick(METEOR, tick(t).build());
            assertTrue(b.holdLastRotation(), "tick " + t);
        }
        b.preTick(METEOR, tick(12).build());
        assertFalse(b.holdLastRotation());

        CrystalBrain noRotate = new CrystalBrain();
        noRotate.preTick(NO_ROTATE, tick(1).crystals(crystal(1, 8, 1)).build());
        noRotate.preTick(NO_ROTATE, tick(2).build());
        assertFalse(noRotate.holdLastRotation());
    }

    // P1: Switching

    @Test
    void normalSwitchSwapsToTheCrystalsOnlyWhenNoHandHoldsThem() {
        // Lines 1036-1043: the offhand first; otherwise swap (no swap back) and place with the main hand.
        Action hotbar = only(once(METEOR, tick(1).hands(hands(true, false, false)).candidates(spot(9, 8, 1))));
        Action main = only(once(METEOR, tick(1).hands(hands(true, true, false)).candidates(spot(9, 8, 1))));
        Action off = only(once(METEOR, tick(1).hands(hands(true, false, true)).candidates(spot(9, 8, 1))));
        Action both = only(once(METEOR, tick(1).hands(hands(true, true, true)).candidates(spot(9, 8, 1))));

        assertTrue(hotbar.switchToCrystals());
        assertEquals(Action.Hand.MAIN, hotbar.hand());
        assertFalse(main.switchToCrystals());
        assertEquals(Action.Hand.MAIN, main.hand());
        assertFalse(off.switchToCrystals());
        assertEquals(Action.Hand.OFF, off.hand());
        assertFalse(both.switchToCrystals());
        assertEquals(Action.Hand.OFF, both.hand());
    }

    @Test
    void withAutoSwitchNoneOnlyCrystalsInHandArePlaced() {
        // Line 919.
        CrystalSettings none = METEOR.toBuilder().autoSwitch(AutoSwitch.NONE).build();

        assertNothing(once(none, tick(1).hands(hands(true, false, false)).candidates(spot(9, 8, 1))));
        Action main = only(once(none, tick(1).hands(hands(true, true, false)).candidates(spot(9, 8, 1))));
        assertFalse(main.switchToCrystals());
    }

    @Test
    void noGapSwitchKeepsAGoldenAppleInHand() {
        // Lines 912-916: with Normal switching, a golden apple in either hand stops placing.
        CrystalTick.Hands gappleOff = with(hands(true, true, false), true, false);
        CrystalTick.Hands gappleMain = with(hands(true, false, false), true, false);

        assertNothing(once(METEOR, tick(1).hands(gappleMain).candidates(spot(9, 8, 1))));
        assertNothing(once(METEOR, tick(1).hands(gappleOff).candidates(spot(9, 8, 1))));
        assertPlaces(9, once(METEOR.toBuilder().noGapSwitch(false).build(), tick(1).hands(gappleMain).candidates(spot(9, 8, 1))));
        assertPlaces(9, once(METEOR.toBuilder().autoSwitch(AutoSwitch.NONE).build(), tick(1).hands(gappleOff).candidates(spot(9, 8, 1))));
    }

    @Test
    void noBowSwitchKeepsABowInHand() {
        // Line 918.
        CrystalTick.Hands bowMain = with(hands(true, false, false), false, true);
        CrystalTick.Hands bowOff = with(hands(true, true, false), false, true);

        assertNothing(once(METEOR, tick(1).hands(bowMain).candidates(spot(9, 8, 1))));
        assertNothing(once(METEOR, tick(1).hands(bowOff).candidates(spot(9, 8, 1))));
        assertPlaces(9, once(METEOR.toBuilder().noBowSwitch(false).build(), tick(1).hands(bowMain).candidates(spot(9, 8, 1))));
        assertPlaces(9, once(METEOR.toBuilder().autoSwitch(AutoSwitch.NONE).build(), tick(1).hands(bowOff).candidates(spot(9, 8, 1))));
    }

    @Test
    void antiWeaknessSwapsToAWeaponAndDoesNotAttackThatTime() {
        // Lines 826-838. The crystal is not attacked, so it is not waiting; the switch timer of 1 has run
        // out by the next pre-tick's break (lines 687, 771).
        CrystalBrain b = new CrystalBrain();
        CrystalSeen c = crystal(1, 8, 1);

        Action swap = only(b.preTick(METEOR, tick(1).hands(weakened(0, NO, false, true)).crystals(c)
            .candidates(spot(9, 8, 1)).build()));
        assertEquals(Decision.swapWeapon(1), swap.decision());
        assertEquals(swap.decision(), b.lastDecision());
        // Fast-break ignores the switch timer (line 740).
        assertTrue(b.crystalAdded(crystal(2, 8, 1), 20, weakened(0, NO, true, true)).isPresent());
        assertBreaks(1, b.preTick(METEOR, tick(2).hands(weakened(0, NO, true, true)).crystals(c, crystal(2, 8, 1)).build()));
    }

    @Test
    void antiWeaknessAppliesUnlessStrengthIsStronger() {
        // Line 831: weakness, and no strength or strength's amplifier <= weakness's.
        CrystalSeen c = crystal(1, 8, 1);

        assertEquals(Kind.SWAP_WEAPON, only(once(METEOR, tick(1).hands(weakened(0, 0, false, true)).crystals(c))).decision().kind());
        assertEquals(Kind.SWAP_WEAPON, only(once(METEOR, tick(1).hands(weakened(1, 0, false, true)).crystals(c))).decision().kind());
        assertBreaks(1, once(METEOR, tick(1).hands(weakened(0, 1, false, true)).crystals(c)));
        assertBreaks(1, once(METEOR, tick(1).hands(weakened(0, NO, true, true)).crystals(c)));
        assertBreaks(1, once(METEOR.toBuilder().antiWeakness(false).build(), tick(1).hands(weakened(0, NO, false, true)).crystals(c)));
    }

    @Test
    void antiWeaknessWithNothingToSwapToDoesNothingAndTheCrystalStillBlocksPlacing() {
        // Line 835: the swap fails, doBreak returns; lines 921-924 then stop the placement.
        assertNothing(once(NO_ROTATE, tick(1).hands(weakened(0, NO, false, false)).crystals(crystal(1, 8, 1))
            .candidates(spot(9, 8, 1))));
    }

    @Test
    void antiWeaknessAlsoGuardsFastBreak() {
        // Fast-break goes through the same doBreak(crystal) (line 742): swap, no attack, nothing recorded.
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).build());
        CrystalSeen c = crystal(1, 8, 1);

        Action swap = b.crystalAdded(c, 20, weakened(0, NO, false, true)).orElseThrow();
        assertEquals(Decision.swapWeapon(1), swap.decision());
        assertEquals(swap.decision(), b.lastDecision());
        // A swap is no rotation: the next crystal can still be fast-broken, now with the weapon in hand.
        assertTrue(b.crystalAdded(crystal(2, 8, 1), 20, weakened(0, NO, true, true)).isPresent());
        // Crystal 1 was not attacked: not waiting, so the next pre-tick breaks it (2 is waiting).
        assertBreaks(1, b.preTick(METEOR, tick(2).hands(weakened(0, NO, true, true)).crystals(c, crystal(2, 8, 1)).build()));
    }

    // P1: Swing

    @Test
    void theAttackSwingsTheHandHoldingTheCrystals() {
        // Lines 885-886: findInHotbar(END_CRYSTAL).getHand(), the main hand when that is not a hand.
        CrystalSeen c = crystal(1, 8, 1);

        assertEquals(Action.Hand.OFF, only(once(METEOR, tick(1).hands(hands(true, false, true)).crystals(c))).hand());
        assertEquals(Action.Hand.MAIN, only(once(METEOR, tick(1).hands(hands(true, true, false)).crystals(c))).hand());
        assertEquals(Action.Hand.MAIN, only(once(METEOR, tick(1).hands(hands(true, false, false)).crystals(c))).hand());
        assertEquals(Action.Hand.MAIN, only(once(METEOR, tick(1).hands(hands(false, false, false)).crystals(c))).hand());
    }

    // P1: Pauses

    @Test
    void atOrBelowPauseHealthNothingIsPlacedOrBroken() {
        // Line 1161: health + absorption <= pause-health 5.
        assertNothing(once(NO_ROTATE, tick(1).health(5).crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
        assertEquals(2, once(NO_ROTATE, tick(1).health(5.25).crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))).size());
    }

    @Test
    void pauseOnUsePausesOnlyPlacingByDefault() {
        // Lines 1154-1156 with pause-on-use Place.
        assertBreaks(1, once(NO_ROTATE, tick(1).usingItem().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
        assertNothing(once(NO_ROTATE, tick(1).usingItem().candidates(spot(9, 8, 1))));
        assertPlaces(9, once(NO_ROTATE, tick(1).candidates(spot(9, 8, 1))));
        assertNothing(once(NO_ROTATE.toBuilder().pauseOnUse(PauseMode.BOTH).build(),
            tick(1).usingItem().crystals(crystal(1, 8, 1))));
        assertEquals(2, once(NO_ROTATE.toBuilder().pauseOnUse(PauseMode.NONE).build(),
            tick(1).usingItem().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))).size());
    }

    @Test
    void pauseOnMineIsOffByDefault() {
        // Line 1160 with pause-on-mine None.
        assertEquals(2, once(NO_ROTATE, tick(1).mining().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))).size());
        assertNothing(once(NO_ROTATE.toBuilder().pauseOnMine(PauseMode.BOTH).build(),
            tick(1).mining().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
    }

    @Test
    void lagAndThePauseModulesPauseBoth() {
        // Lines 1158-1159.
        assertNothing(once(NO_ROTATE, tick(1).lagging().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
        assertEquals(2, once(NO_ROTATE.toBuilder().pauseOnLag(false).build(),
            tick(1).lagging().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))).size());
        assertNothing(once(NO_ROTATE, tick(1).pauseModule().crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
    }

    @Test
    void theSettingsTurnPlacingAndBreakingOff() {
        // Lines 771, 904. With break off, a crystal Meteor would break still stops placing (lines 921-924).
        CrystalSettings noBreak = NO_ROTATE.toBuilder().breakCrystals(false).build();

        assertNothing(once(noBreak, tick(1).crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
        assertPlaces(9, once(noBreak, tick(1).candidates(spot(9, 8, 1))));
        assertBreaks(1, once(NO_ROTATE.toBuilder().place(false).build(),
            tick(1).crystals(crystal(1, 8, 1)).candidates(spot(9, 8, 1))));
    }

    // Q1

    @Test
    void theBoxIgnoresCrystalsWeAttackedThatStillStand() {
        // Line 1257: entities in Meteor's removed set do not count.
        CrystalBrain b = new CrystalBrain();
        CrystalSeen hit = crystal(1, 8, 1);
        assertBreaks(1, b.preTick(METEOR, tick(1).crystals(hit).build()));
        b.attackSent();

        assertPlaces(9, b.preTick(METEOR, tick(2).crystals(hit).candidates(boxed(spot(9, 8, 1), Set.of(1), false)).build()));
        // A crystal we did not attack does count (it deals nothing, so it does not stop placing altogether).
        assertNothing(once(METEOR, tick(1).crystals(crystal(2, 0, 0)).candidates(boxed(spot(9, 8, 1), Set.of(2), false))));
    }

    @Test
    void noGapSwitchDoesNotApplyWithCrystalsInTheOffhand() {
        // Line 912: offItem != END_CRYSTAL.
        CrystalTick.Hands gappleMainCrystalsOff = with(hands(true, false, true), true, false);

        Action a = only(once(METEOR, tick(1).hands(gappleMainCrystalsOff).candidates(spot(9, 8, 1))));
        assertEquals(Action.Hand.OFF, a.hand());
    }

    @Test
    void theAttackCounterGoesUpWhenTheAttackIsSentNotWhenItIsDecided() {
        // Line 891, inside the attack (the Rotations callback with rotate on).
        CrystalBrain b = new CrystalBrain();
        b.preTick(NO_ROTATE, tick(1).build());
        for (int id = 1; id <= 30; id++) {
            assertTrue(b.crystalAdded(crystal(id, 8, 1), 20, HANDS).isPresent(), "decision " + id);
        }
        for (int i = 0; i < 25; i++) b.attackSent();

        assertTrue(b.crystalAdded(crystal(31, 8, 1), 20, HANDS).isEmpty());
    }

    @Test
    void withoutCrystalsInTheHotbarOrOffhandBreakingStillRuns() {
        // Line 908 returns from doPlace only.
        assertBreaks(1, once(NO_ROTATE, tick(1).hands(hands(false, false, false)).crystals(crystal(1, 8, 1))
            .candidates(spot(9, 8, 1))));
        assertNothing(once(NO_ROTATE, tick(1).hands(hands(false, false, false)).candidates(spot(9, 8, 1))));
    }

    // Phases: Meteor decides the break and the placing gate at HIGH, and the spot from the scan

    @Test
    void aBreakAtHighClosesThePlacingGateWithRotateOn() {
        CrystalBrain b = new CrystalBrain();

        assertEquals(Kind.BREAK, b.breakPhase(METEOR, tick(1).crystals(crystal(1, 8, 1)).build()).orElseThrow().decision().kind());
        assertFalse(b.wantsPlacement());
        assertTrue(b.placePhase(20, List.of(spot(9, 8, 1))).isEmpty());
    }

    @Test
    void thePlacingGateReadsWhatWasMeasuredAtHigh() {
        // Hands (lines 693-694, 912-919), pause-health (line 1161 via doPlace at HIGH) and one at a time.
        CrystalBrain gapple = new CrystalBrain();
        gapple.breakPhase(METEOR, tick(1).hands(with(hands(true, false, false), true, false)).build());
        assertFalse(gapple.wantsPlacement());
        assertTrue(gapple.placePhase(20, List.of(spot(9, 8, 1))).isEmpty());

        CrystalBrain paused = new CrystalBrain();
        paused.breakPhase(METEOR, tick(1).health(5).build());
        assertFalse(paused.wantsPlacement());
        assertTrue(paused.placePhase(20, List.of(spot(9, 8, 1))).isEmpty());

        CrystalBrain open = new CrystalBrain();
        open.breakPhase(METEOR, tick(1).build());
        assertTrue(open.wantsPlacement());
        assertPlaces(9, List.of(open.placePhase(20, List.of(spot(9, 8, 1))).orElseThrow()));
        assertFalse(open.wantsPlacement());
    }

    @Test
    void theSpotsAreCheckedWithTheHealthReadDuringTheScan() {
        // Line 953 reads health inside the scan: at 6, self 6 fails anti-suicide and self 5.75 passes.
        CrystalBrain b = new CrystalBrain();
        b.breakPhase(METEOR, tick(1).health(20).build());

        assertPlaces(2, List.of(b.placePhase(6, List.of(spot(1, 20, 6), spot(2, 10, 5.75))).orElseThrow()));
    }

    @Test
    void thePhasesGoInOrderOncePerPreTick() {
        CrystalBrain b = new CrystalBrain();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> b.placePhase(20, List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> b.breakPhase(METEOR, tick(1).candidates(spot(9, 8, 1)).build()));

        b.breakPhase(METEOR, tick(1).build());
        b.placePhase(20, List.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> b.placePhase(20, List.of()));
        // The next pre-tick opens a new place phase.
        b.breakPhase(METEOR, tick(2).build());
        assertTrue(b.placePhase(20, List.of()).isEmpty());
    }

    // Order of calls

    @Test
    void preTicksMustGoForward() {
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(5).build());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> b.preTick(METEOR, tick(5).build()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> b.placed(9, 0));
        // A crystal added before the first pre-tick waits for it: no targets yet, nothing pending.
        CrystalBrain fresh = new CrystalBrain();
        assertTrue(fresh.crystalAdded(crystal(1, 8, 1), 20, HANDS).isEmpty());
        assertBreaks(1, fresh.preTick(METEOR, tick(1).crystals(crystal(1, 8, 1)).build()));
    }
}
