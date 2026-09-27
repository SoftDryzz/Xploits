package com.xploits.pvp.crystal.core;

import com.xploits.pvp.crystal.core.CrystalSettings.AutoSwitch;
import com.xploits.pvp.crystal.core.CrystalSettings.PauseMode;
import com.xploits.pvp.crystal.core.Decision.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertBreaks;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.only;
import static com.xploits.pvp.crystal.core.CrystalSetting.ANTI_SUICIDE;
import static com.xploits.pvp.crystal.core.CrystalSetting.ANTI_WEAKNESS;
import static com.xploits.pvp.crystal.core.CrystalSetting.ATTACK_FREQUENCY;
import static com.xploits.pvp.crystal.core.CrystalSetting.AUTO_SWITCH;
import static com.xploits.pvp.crystal.core.CrystalSetting.BREAK;
import static com.xploits.pvp.crystal.core.CrystalSetting.BREAK_ATTEMPTS;
import static com.xploits.pvp.crystal.core.CrystalSetting.BREAK_RANGE;
import static com.xploits.pvp.crystal.core.CrystalSetting.BREAK_WALLS_RANGE;
import static com.xploits.pvp.crystal.core.CrystalSetting.FACE_PLACE;
import static com.xploits.pvp.crystal.core.CrystalSetting.FACE_PLACE_DURABILITY;
import static com.xploits.pvp.crystal.core.CrystalSetting.FACE_PLACE_HEALTH;
import static com.xploits.pvp.crystal.core.CrystalSetting.FAST_BREAK;
import static com.xploits.pvp.crystal.core.CrystalSetting.MAX_DAMAGE;
import static com.xploits.pvp.crystal.core.CrystalSetting.MIN_DAMAGE;
import static com.xploits.pvp.crystal.core.CrystalSetting.NO_BOW_SWITCH;
import static com.xploits.pvp.crystal.core.CrystalSetting.NO_GAP_SWITCH;
import static com.xploits.pvp.crystal.core.CrystalSetting.PAUSE_HEALTH;
import static com.xploits.pvp.crystal.core.CrystalSetting.PAUSE_MODULES;
import static com.xploits.pvp.crystal.core.CrystalSetting.PAUSE_ON_LAG;
import static com.xploits.pvp.crystal.core.CrystalSetting.PAUSE_ON_MINE;
import static com.xploits.pvp.crystal.core.CrystalSetting.PAUSE_ON_USE;
import static com.xploits.pvp.crystal.core.CrystalSetting.PLACE;
import static com.xploits.pvp.crystal.core.CrystalSetting.PLACE_RANGE;
import static com.xploits.pvp.crystal.core.CrystalSetting.PLACE_WALLS_RANGE;
import static com.xploits.pvp.crystal.core.CrystalSetting.RESERVE;
import static com.xploits.pvp.crystal.core.CrystalSetting.RISK;
import static com.xploits.pvp.crystal.core.CrystalSetting.ROTATE;
import static com.xploits.pvp.crystal.core.CrystalSetting.SAFE_SELF_DAMAGE;
import static com.xploits.pvp.crystal.core.CrystalSetting.SELF_BUDGET;
import static com.xploits.pvp.crystal.core.CrystalSetting.SWING_MODE;
import static com.xploits.pvp.crystal.core.CrystalSetting.TARGET_RANGE;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.METEOR;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings coverage matrix (R2-4): for every setting crystal-aura++ exposes ({@link CrystalSetting}), a
 * test with a value other than the default that shows the decision change exactly as Meteor's CrystalAura
 * changes with that same value, and the boundary, with the line of {@code CrystalAura.java} (1.21.11 sources)
 * the behaviour comes from. Each test names the settings it covers with {@link Covers};
 * {@code CrystalSettingsCoverageGuardTest} fails when a setting has none.
 *
 * <p>The budget is off ({@link Crystals#METEOR}) except for the Safety group; what is left is Meteor's own
 * rules. {@code MeteorParityPropertyTest} drives every setting at random values against an independent
 * transcription of Meteor's code.
 */
class CrystalSettingsCoverageTest {
    private static final int NO = CrystalTick.Hands.NO_EFFECT;

    private static List<Action> once(CrystalSettings s, Crystals.Tick t) {
        return new CrystalBrain().preTick(s, t.build());
    }

    private static CrystalSettings.Builder meteor() {
        return METEOR.toBuilder();
    }

    private static TargetView at(double squaredDistance) {
        return new TargetView(ENEMY, squaredDistance, 20, TargetView.NO_ARMOR, false, true, false);
    }

    private static TargetView wearing(double armorPercent) {
        return new TargetView(ENEMY, 9, 20, armorPercent, false, true, false);
    }

    // General

    @Test
    @Covers(TARGET_RANGE)
    void targetRangeFiveTakesAPlayerAtFiveAndNoFurther() {
        // Line 1250: left out when squaredDistanceTo(player) > target-range * target-range.
        CrystalSettings five = meteor().targetRange(5).build();
        assertPlaces(1, once(five, tick(1).targets(at(25)).candidates(spot(1, 6, 1))));
        List<Action> past = once(five, tick(1).targets(at(Math.nextUp(25.0))).candidates(spot(1, 6, 1)));
        assertNothing(past);
        // The same player is a target at the default 10: the setting is what changed.
        assertPlaces(1, once(METEOR, tick(1).targets(at(Math.nextUp(25.0))).candidates(spot(1, 6, 1))));
    }

    @Test
    @Covers(TARGET_RANGE)
    void targetRangeZeroTakesOnlyAPlayerAtNoDistance() {
        CrystalSettings zero = meteor().targetRange(0).build();
        assertPlaces(1, once(zero, tick(1).targets(at(0)).candidates(spot(1, 6, 1))));
        assertNothing(once(zero, tick(1).targets(at(Double.MIN_VALUE)).candidates(spot(1, 6, 1))));
    }

    @Test
    @Covers(MIN_DAMAGE)
    void minDamageFourPlacesFromFour() {
        // Lines 959-961: a spot is left out when its damage < min-damage.
        CrystalSettings four = meteor().minDamage(4).build();
        assertPlaces(1, once(four, tick(1).candidates(spot(1, 4, 1))));
        assertNothing(once(four, tick(1).candidates(spot(1, 3.75, 1))));
        assertNothing(once(METEOR, tick(1).candidates(spot(1, 4, 1))));
    }

    @Test
    @Covers(MIN_DAMAGE)
    void minDamageFourBreaksFromFour() {
        // Lines 817-819.
        CrystalSettings four = meteor().minDamage(4).build();
        assertBreaks(1, once(four, tick(1).crystals(crystal(1, 4, 1))));
        assertNothing(once(four, tick(1).crystals(crystal(1, 3.75, 1))));
        assertNothing(once(METEOR, tick(1).crystals(crystal(1, 4, 1))));
    }

    @Test
    @Covers(MIN_DAMAGE)
    void fastBreakNeedsMoreThanMinDamageFour() {
        // Line 742: damage > min-damage, strictly.
        CrystalSettings four = meteor().minDamage(4).build();
        CrystalBrain b = new CrystalBrain();
        b.preTick(four, tick(1).build());
        assertTrue(b.crystalAdded(four, crystal(1, 4, 1), 20, HANDS).isEmpty());
        assertEquals(Kind.BREAK, b.crystalAdded(four, crystal(2, 4.25, 1), 20, HANDS).orElseThrow().decision().kind());
    }

    @Test
    @Covers(MIN_DAMAGE)
    void minDamageZeroStillNeedsSomeDamage() {
        // Lines 927, 972 and 982: the best damage starts at 0 and a spot must beat it; 774-781 likewise.
        CrystalSettings zero = meteor().minDamage(0).build();
        assertNothing(once(zero, tick(1).candidates(spot(1, 0, 1))));
        assertPlaces(1, once(zero, tick(1).candidates(spot(1, 0.25, 1))));
        assertNothing(once(zero, tick(1).crystals(crystal(1, 0, 1))));
        assertBreaks(1, once(zero, tick(1).crystals(crystal(1, 0.25, 1))));
    }

    @Test
    @Covers({MIN_DAMAGE, FACE_PLACE})
    void faceplacingWithMinDamageOneKeepsOne() {
        // Lines 817 and 959: face-placing lowers the minimum to min(min-damage, 1.5), never raises it.
        CrystalSettings one = meteor().minDamage(1).build();
        TargetView low = player(ENEMY, 3, 8);
        assertPlaces(1, once(one, tick(1).targets(low).candidates(spot(1, 1, 1))));
        assertNothing(once(one, tick(1).targets(low).candidates(spot(1, 0.75, 1))));
    }

    @Test
    @Covers(MAX_DAMAGE)
    void maxDamageEightAllowsEightToYou() {
        // Lines 953 (place) and 812 (break): left out when self damage > max-damage.
        CrystalSettings eight = meteor().maxDamage(8).build();
        assertPlaces(1, once(eight, tick(1).candidates(spot(1, 6, 8))));
        assertNothing(once(eight, tick(1).candidates(spot(1, 6, 8.25))));
        assertBreaks(1, once(eight, tick(1).crystals(crystal(1, 6, 8))));
        assertNothing(once(eight, tick(1).crystals(crystal(1, 6, 8.25))));
        assertNothing(once(METEOR, tick(1).candidates(spot(1, 6, 8))));
    }

    @Test
    @Covers(MAX_DAMAGE)
    void maxDamageZeroAllowsOnlyHarmlessCrystals() {
        CrystalSettings zero = meteor().maxDamage(0).build();
        assertPlaces(1, once(zero, tick(1).candidates(spot(1, 6, 0))));
        assertNothing(once(zero, tick(1).candidates(spot(1, 6, 0.25))));
        assertBreaks(1, once(zero, tick(1).crystals(crystal(1, 6, 0))));
        assertNothing(once(zero, tick(1).crystals(crystal(1, 6, 0.25))));
    }

    @Test
    @Covers(ANTI_SUICIDE)
    void antiSuicideOffPlacesAndBreaksWhatCouldKillYou() {
        // Lines 953 and 812: with anti-suicide, left out when self damage >= your health.
        CrystalSettings on = meteor().maxDamage(36).build();
        CrystalSettings off = on.toBuilder().antiSuicide(false).build();
        assertNothing(once(on, tick(1).health(7).candidates(spot(1, 6, 7))));
        assertPlaces(1, once(on, tick(1).health(7).candidates(spot(1, 6, 6.75))));
        assertPlaces(1, once(off, tick(1).health(7).candidates(spot(1, 6, 7))));
        assertPlaces(1, once(off, tick(1).health(7).candidates(spot(1, 6, 12))));
        assertNothing(once(on, tick(1).health(7).crystals(crystal(1, 6, 7))));
        assertBreaks(1, once(on, tick(1).health(7).crystals(crystal(1, 6, 6.75))));
        assertBreaks(1, once(off, tick(1).health(7).crystals(crystal(1, 6, 7))));
    }

    @Test
    @Covers(ROTATE)
    void rotateOffBreaksAndPlacesInOneTick() {
        // Lines 716-718: place runs only if break did not rotate; setRotation (755-766) is called only with
        // rotate on (846-859, 992-1002), so with it off the attack (861) and the placement (1004) both happen.
        Crystals.Tick t = tick(1).crystals(crystal(1, 8, 1)).candidates(spot(2, 7, 1));
        List<Action> off = once(meteor().rotate(false).build(), t);
        assertEquals(2, off.size(), off.toString());
        assertEquals(Kind.BREAK, off.get(0).decision().kind());
        assertEquals(1, off.get(0).decision().ref());
        assertEquals(Kind.PLACE, off.get(1).decision().kind());
        assertEquals(2, off.get(1).decision().ref());
        assertBreaks(1, once(METEOR, tick(1).crystals(crystal(1, 8, 1)).candidates(spot(2, 7, 1))));
    }

    @Test
    @Covers(ROTATE)
    void rotateOffFastBreaksEveryNewCrystal() {
        // Line 740: fast-break needs !didRotateThisTick, which only a rotation sets.
        CrystalSettings off = meteor().rotate(false).build();
        CrystalBrain b = new CrystalBrain();
        b.preTick(off, tick(1).build());
        assertTrue(b.crystalAdded(off, crystal(1, 8, 1), 20, HANDS).isPresent());
        assertTrue(b.crystalAdded(off, crystal(2, 8, 1), 20, HANDS).isPresent());
        CrystalBrain on = new CrystalBrain();
        on.preTick(METEOR, tick(1).build());
        assertTrue(on.crystalAdded(METEOR, crystal(1, 8, 1), 20, HANDS).isPresent());
        assertTrue(on.crystalAdded(METEOR, crystal(2, 8, 1), 20, HANDS).isEmpty());
    }

    @Test
    @Covers(ROTATE)
    void rotateOffNeverHoldsTheLastRotation() {
        // Line 725: the hold needs rotate on.
        CrystalBrain on = new CrystalBrain();
        on.preTick(METEOR, tick(1).crystals(crystal(1, 8, 1)).build());
        on.preTick(METEOR, tick(2).build());
        assertTrue(on.holdLastRotation());
        CrystalSettings offSettings = meteor().rotate(false).build();
        CrystalBrain off = new CrystalBrain();
        off.preTick(offSettings, tick(1).crystals(crystal(1, 8, 1)).build());
        off.preTick(offSettings, tick(2).build());
        assertFalse(off.holdLastRotation());
    }

    @Test
    @Covers(AUTO_SWITCH)
    void autoSwitchNoneNeedsCrystalsInHand() {
        // Line 919: with None, no placing unless a hand holds crystals; line 1041: and never a swap.
        CrystalSettings none = meteor().autoSwitch(AutoSwitch.NONE).build();
        CrystalTick.Hands hotbarOnly = hands(true, false, false);
        assertNothing(once(none, tick(1).hands(hotbarOnly).candidates(spot(1, 6, 1))));
        Action normal = only(once(METEOR, tick(1).hands(hotbarOnly).candidates(spot(1, 6, 1))));
        assertTrue(normal.switchToCrystals());
        assertEquals(Action.Hand.MAIN, normal.hand());

        Action main = only(once(none, tick(1).candidates(spot(1, 6, 1))));
        assertFalse(main.switchToCrystals());
        assertEquals(Action.Hand.MAIN, main.hand());
        Action off = only(once(none, tick(1).hands(hands(true, false, true)).candidates(spot(1, 6, 1))));
        assertFalse(off.switchToCrystals());
        assertEquals(Action.Hand.OFF, off.hand());
    }

    @Test
    @Covers(AUTO_SWITCH)
    void autoSwitchNormalDoesNotSwapToWhatIsHeld() {
        // Lines 1036-1043: findInHotbar finds the offhand or the held slot first, and swapping to the held
        // slot changes nothing.
        assertFalse(only(once(METEOR, tick(1).candidates(spot(1, 6, 1)))).switchToCrystals());
        assertFalse(only(once(METEOR, tick(1).hands(hands(true, false, true)).candidates(spot(1, 6, 1)))).switchToCrystals());
    }

    @Test
    @Covers(NO_GAP_SWITCH)
    void noGapSwitchOffPlacesWithAGoldenAppleInHand() {
        // Lines 912-916.
        CrystalTick.Hands gapple = with(HANDS, true, false);
        assertNothing(once(METEOR, tick(1).hands(gapple).candidates(spot(1, 6, 1))));
        assertPlaces(1, once(meteor().noGapSwitch(false).build(), tick(1).hands(gapple).candidates(spot(1, 6, 1))));
        // Line 912: not with crystals in the offhand, and only with Normal (911-912).
        assertPlaces(1, once(METEOR, tick(1).hands(with(hands(true, false, true), true, false)).candidates(spot(1, 6, 1))));
        assertPlaces(1, once(meteor().autoSwitch(AutoSwitch.NONE).build(), tick(1).hands(gapple).candidates(spot(1, 6, 1))));
    }

    @Test
    @Covers(NO_BOW_SWITCH)
    void noBowSwitchOffPlacesWithABowInHand() {
        // Line 918, inside auto-switch not None (911).
        CrystalTick.Hands bow = with(HANDS, false, true);
        assertNothing(once(METEOR, tick(1).hands(bow).candidates(spot(1, 6, 1))));
        assertPlaces(1, once(meteor().noBowSwitch(false).build(), tick(1).hands(bow).candidates(spot(1, 6, 1))));
        assertPlaces(1, once(meteor().autoSwitch(AutoSwitch.NONE).build(), tick(1).hands(bow).candidates(spot(1, 6, 1))));
    }

    @Test
    @Covers(ANTI_WEAKNESS)
    void antiWeaknessOffAttacksWhateverTheHand() {
        // Line 826: without anti-weakness the attack goes out even if the hand cannot hurt the crystal.
        CrystalTick.Hands weak = weakened(0, NO, false, true);
        assertEquals(Kind.SWAP_WEAPON, only(once(METEOR, tick(1).hands(weak).crystals(crystal(1, 8, 1)))).decision().kind());
        CrystalSettings off = meteor().antiWeakness(false).build();
        assertBreaks(1, once(off, tick(1).hands(weak).crystals(crystal(1, 8, 1))));
        CrystalBrain b = new CrystalBrain();
        b.preTick(off, tick(1).hands(weak).build());
        assertEquals(Kind.BREAK, b.crystalAdded(off, crystal(2, 8, 1), 20, weak).orElseThrow().decision().kind());
    }

    @Test
    @Covers(ANTI_WEAKNESS)
    void antiWeaknessGivesWayToAtLeastAsMuchStrength() {
        // Line 831: only while there is no Strength or Strength <= Weakness.
        assertEquals(Kind.SWAP_WEAPON,
            only(once(METEOR, tick(1).hands(weakened(1, 1, false, true)).crystals(crystal(1, 8, 1)))).decision().kind());
        assertBreaks(1, once(METEOR, tick(1).hands(weakened(1, 2, false, true)).crystals(crystal(1, 8, 1))));
    }

    @Test
    @Covers(SWING_MODE)
    void swingModeSaysWhoSeesTheSwingOfTheCrystalHand() {
        // Lines 1382-1395, used at 888-889 (attack) and 1051-1052 (placement).
        assertTrue(SwingMode.BOTH.client() && SwingMode.BOTH.packet());
        assertTrue(SwingMode.CLIENT.client() && !SwingMode.CLIENT.packet());
        assertTrue(!SwingMode.PACKET.client() && SwingMode.PACKET.packet());
        assertTrue(!SwingMode.NONE.client() && !SwingMode.NONE.packet());
        // The hand swung is the crystal hand (lines 885-886, 1043): the offhand when it holds them.
        CrystalTick.Hands off = hands(true, false, true);
        assertEquals(Action.Hand.OFF, only(once(METEOR, tick(1).hands(off).crystals(crystal(1, 8, 1)))).hand());
        assertEquals(Action.Hand.MAIN, only(once(METEOR, tick(1).crystals(crystal(1, 8, 1)))).hand());
    }

    // Place

    @Test
    @Covers(PLACE)
    void placeOffStillBreaks() {
        // Line 904: doPlace returns at once; doBreak (770-789) does not read it.
        CrystalSettings off = meteor().place(false).build();
        assertNothing(once(off, tick(1).candidates(spot(1, 6, 1))));
        assertBreaks(1, once(off, tick(1).crystals(crystal(1, 8, 1))));
        CrystalBrain b = new CrystalBrain();
        b.breakPhase(off, tick(1).build());
        assertFalse(b.wantsPlacement());
    }

    /** Out of range for a spot, with the break ranges set apart (5.5 and 6) so they can never stand in. */
    private static boolean spotOut(boolean behindWall, double squared, double placeRange, double placeWallsRange) {
        return Reach.outOfRange(squared, Reach.rangeFor(true, behindWall, placeRange, placeWallsRange, 5.5, 6));
    }

    /** Out of range for a crystal, with the place ranges set apart (5.5 and 6). */
    private static boolean crystalOut(boolean behindWall, double squared, double breakRange, double breakWallsRange) {
        return Reach.outOfRange(squared, Reach.rangeFor(false, behindWall, 5.5, 6, breakRange, breakWallsRange));
    }

    @Test
    @Covers({PLACE_RANGE, PLACE_WALLS_RANGE, BREAK_RANGE, BREAK_WALLS_RANGE})
    void eachOfTheFourRangesIsUsedWhereMeteorUsesIt() {
        // Line 949 measures a spot against the place ranges, line 807 a crystal against the break ranges, and
        // lines 1169-1171 pick the walls range behind a wall. Four distinct values, so no swap can pass.
        assertEquals(1, Reach.rangeFor(true, false, 1, 2, 3, 4), 0.0);
        assertEquals(2, Reach.rangeFor(true, true, 1, 2, 3, 4), 0.0);
        assertEquals(3, Reach.rangeFor(false, false, 1, 2, 3, 4), 0.0);
        assertEquals(4, Reach.rangeFor(false, true, 1, 2, 3, 4), 0.0);
    }

    @Test
    @Covers(PLACE_RANGE)
    void placeRangeThreeReachesThreeFromTheFeet() {
        // Lines 947-949 and 1171: PlayerUtils.isWithin is a squared distance from the feet <= r * r.
        assertFalse(spotOut(false, 9, 3, 4.5));
        assertTrue(spotOut(false, Math.nextUp(9.0), 3, 4.5));
        assertFalse(spotOut(false, Math.nextUp(9.0), 4.5, 4.5));
        // The brain never places on a spot the adapter measured out of range.
        assertNothing(once(METEOR, tick(1).candidates(outOfRange(spot(1, 6, 1)))));
    }

    @Test
    @Covers(PLACE_WALLS_RANGE)
    void placeWallsRangeTwoAppliesOnlyBehindAWall() {
        // Lines 1169-1170: when the eye raycast does not end on the block, the walls range applies.
        assertFalse(spotOut(true, 4, 4.5, 2));
        assertTrue(spotOut(true, Math.nextUp(4.0), 4.5, 2));
        assertFalse(spotOut(false, 16, 4.5, 2));
    }

    @Test
    @Covers(FACE_PLACE)
    void facePlaceOffKeepsMinDamage() {
        // Line 1128.
        TargetView low = player(ENEMY, 3, 8);
        assertPlaces(1, once(METEOR, tick(1).targets(low).candidates(spot(1, 1.5, 1))));
        assertNothing(once(meteor().facePlace(false).build(), tick(1).targets(low).candidates(spot(1, 1.5, 1))));
        assertNothing(once(meteor().facePlace(false).build(), tick(1).targets(low).crystals(crystal(1, 1.5, 1))));
    }

    @Test
    @Covers(FACE_PLACE_HEALTH)
    void facePlaceHealthTwelveStartsAtTwelve() {
        // Line 1134: health plus absorption <= face-place-health.
        CrystalSettings twelve = meteor().facePlaceHealth(12).build();
        assertPlaces(1, once(twelve, tick(1).targets(player(ENEMY, 3, 12)).candidates(spot(1, 1.5, 1))));
        assertNothing(once(twelve, tick(1).targets(player(ENEMY, 3, 12.25)).candidates(spot(1, 1.5, 1))));
        assertNothing(once(METEOR, tick(1).targets(player(ENEMY, 3, 12)).candidates(spot(1, 1.5, 1))));
    }

    @Test
    @Covers(FACE_PLACE_DURABILITY)
    void facePlaceDurabilityFiftyStartsAtFiftyPercent() {
        // Line 1143: a worn piece at <= face-place-durability percent.
        CrystalSettings fifty = meteor().facePlaceDurability(50).build();
        assertPlaces(1, once(fifty, tick(1).targets(wearing(50)).candidates(spot(1, 1.5, 1))));
        assertNothing(once(fifty, tick(1).targets(wearing(50.25)).candidates(spot(1, 1.5, 1))));
        assertNothing(once(METEOR, tick(1).targets(wearing(50)).candidates(spot(1, 1.5, 1))));
    }

    // Break

    @Test
    @Covers(BREAK)
    void breakOffStopsBreakingButAStandingCrystalStillBlocksPlacing() {
        // Line 771: doBreak returns. Lines 921-924: the one-at-a-time check calls getBreakDamage, which does
        // not read the break setting, so a crystal Meteor would break still stops a placement.
        CrystalSettings off = meteor().breakCrystals(false).build();
        assertNothing(once(off, tick(1).crystals(crystal(1, 8, 1)).candidates(spot(2, 7, 1))));
        assertPlaces(2, once(off, tick(1).crystals(crystal(1, 5, 1)).candidates(spot(2, 7, 1))));
        // Line 740: fast-break does not read it either.
        CrystalBrain b = new CrystalBrain();
        b.preTick(off, tick(1).build());
        assertEquals(Kind.BREAK, b.crystalAdded(off, crystal(3, 8, 1), 20, HANDS).orElseThrow().decision().kind());
    }

    @Test
    @Covers(BREAK_RANGE)
    void breakRangeThreeReachesThreeFromTheFeet() {
        // Lines 807 and 1171.
        assertFalse(crystalOut(false, 9, 3, 4.5));
        assertTrue(crystalOut(false, Math.nextUp(9.0), 3, 4.5));
        // Out of range: not broken, and no longer blocking a placement (807, then 921-924).
        assertPlaces(2, once(METEOR, tick(1).crystals(outOfBreakRange(crystal(1, 8, 1))).candidates(spot(2, 7, 1))));
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).build());
        assertTrue(b.crystalAdded(METEOR, outOfBreakRange(crystal(1, 8, 1)), 20, HANDS).isEmpty());
    }

    @Test
    @Covers(BREAK_WALLS_RANGE)
    void breakWallsRangeTwoAppliesOnlyBehindAWall() {
        // Lines 1169-1170.
        assertFalse(crystalOut(true, 4, 4.5, 2));
        assertTrue(crystalOut(true, Math.nextUp(4.0), 4.5, 2));
        assertFalse(crystalOut(false, 16, 4.5, 2));
    }

    /** Pre-ticks {@code from..to} with crystal {@code c} standing, each attack sent at once; the breaks seen. */
    private static int hits(CrystalBrain b, CrystalSettings s, CrystalSeen c, int from, int to) {
        int hits = 0;
        for (int t = from; t <= to; t++) {
            List<Action> actions = b.preTick(s, tick(t).crystals(c).build());
            if (!actions.isEmpty() && actions.get(0).decision().kind() == Kind.BREAK) {
                hits++;
                b.attackSent();
            }
        }
        return hits;
    }

    @Test
    @Covers(BREAK_ATTEMPTS)
    void breakAttemptsZeroGivesOneHit() {
        // Line 801: skipped once attempts > break-attempts, so 0 means one hit; the wait is 697-708.
        CrystalSettings zero = meteor().breakAttempts(0).build();
        assertEquals(1, hits(new CrystalBrain(), zero, crystal(1, 8, 1), 1, 30));
        assertEquals(3, hits(new CrystalBrain(), METEOR, crystal(1, 8, 1), 1, 30));
        // Out of attempts it no longer blocks a placement.
        CrystalBrain b = new CrystalBrain();
        hits(b, zero, crystal(1, 8, 1), 1, 5);
        assertPlaces(2, b.preTick(zero, tick(6).crystals(crystal(1, 8, 1)).candidates(spot(2, 7, 1)).build()));
    }

    @Test
    @Covers(BREAK_ATTEMPTS)
    void breakAttemptsFourGivesFiveHitsFivePreTicksApart() {
        // Hits at pre-ticks 1, 6, 11, 16 and 21 (each waits until the 5th pre-tick after it), then none.
        CrystalSettings four = meteor().breakAttempts(4).build();
        CrystalBrain b = new CrystalBrain();
        assertEquals(1, hits(b, four, crystal(1, 8, 1), 1, 5));
        assertEquals(4, hits(b, four, crystal(1, 8, 1), 6, 25));
        assertEquals(0, hits(b, four, crystal(1, 8, 1), 26, 40));
    }

    @Test
    @Covers(ATTACK_FREQUENCY)
    void attackFrequencyTwoAllowsTwoAttacksPerWindow() {
        // Lines 740 (fast-break: attacks < frequency) and 771 (attacks >= frequency); the window resets at the
        // 21st pre-tick (674-678). Rotate off, so nothing but the counter stops a fast-break (740).
        CrystalSettings two = meteor().rotate(false).attackFrequency(2).build();
        CrystalBrain b = new CrystalBrain();
        b.preTick(two, tick(1).build());
        assertTrue(b.crystalAdded(two, crystal(1, 8, 1), 20, HANDS).isPresent());
        b.attackSent();
        assertTrue(b.crystalAdded(two, crystal(2, 8, 1), 20, HANDS).isPresent());
        b.attackSent();
        assertTrue(b.crystalAdded(two, crystal(3, 8, 1), 20, HANDS).isEmpty());
        b.crystalRemoved(1);
        b.crystalRemoved(2);
        for (int t = 2; t <= 20; t++) assertNothing(b.preTick(two, tick(t).crystals(crystal(3, 8, 1)).build()));
        assertBreaks(3, b.preTick(two, tick(21).crystals(crystal(3, 8, 1)).build()));

        // At the default 25 the third crystal is fast-broken too.
        CrystalSettings d = meteor().rotate(false).build();
        CrystalBrain m = new CrystalBrain();
        m.preTick(d, tick(1).build());
        for (int id = 1; id <= 3; id++) {
            assertTrue(m.crystalAdded(d, crystal(id, 8, 1), 20, HANDS).isPresent());
            m.attackSent();
        }
    }

    @Test
    @Covers(ATTACK_FREQUENCY)
    void attackFrequencyOneAllowsOneAttackPerWindow() {
        CrystalSettings one = meteor().attackFrequency(1).build();
        CrystalBrain b = new CrystalBrain();
        assertBreaks(1, b.preTick(one, tick(1).crystals(crystal(1, 8, 1)).build()));
        b.attackSent();
        b.crystalRemoved(1);
        assertNothing(b.preTick(one, tick(2).crystals(crystal(2, 8, 1)).build()));
        assertTrue(b.crystalAdded(one, crystal(3, 8, 1), 20, HANDS).isEmpty());
    }

    @Test
    @Covers(FAST_BREAK)
    void fastBreakOffWaitsForThePreTick() {
        // Line 740.
        CrystalSettings off = meteor().fastBreak(false).build();
        CrystalBrain b = new CrystalBrain();
        b.preTick(off, tick(1).build());
        assertTrue(b.crystalAdded(off, crystal(1, 8, 1), 20, HANDS).isEmpty());
        assertBreaks(1, b.preTick(off, tick(2).crystals(crystal(1, 8, 1)).build()));
    }

    // Pause

    /** Whether the brain breaks a crystal, and separately places, under this pause. */
    private static void assertPauses(CrystalSettings s, Crystals.Tick breaking, Crystals.Tick placing,
                                     boolean breakPaused, boolean placePaused, String what) {
        List<Action> b = once(s, breaking.crystals(crystal(1, 8, 1)));
        assertEquals(breakPaused, b.isEmpty(), what + " break " + b);
        List<Action> p = once(s, placing.candidates(spot(1, 6, 1)));
        assertEquals(placePaused, p.isEmpty(), what + " place " + p);
    }

    @Test
    @Covers(PAUSE_ON_USE)
    void pauseOnUseEveryValue() {
        // Lines 1154-1155 with PauseMode.equals (1377-1379): the process named, or Both.
        for (PauseMode mode : PauseMode.values()) {
            CrystalSettings s = meteor().pauseOnUse(mode).build();
            boolean breaks = mode == PauseMode.BREAK || mode == PauseMode.BOTH;
            boolean places = mode == PauseMode.PLACE || mode == PauseMode.BOTH;
            assertPauses(s, tick(1).usingItem(), tick(1).usingItem(), breaks, places, "using, " + mode);
            assertPauses(s, tick(1), tick(1), false, false, "not using, " + mode);
        }
    }

    @Test
    @Covers(PAUSE_ON_MINE)
    void pauseOnMineEveryValue() {
        // Line 1160.
        for (PauseMode mode : PauseMode.values()) {
            CrystalSettings s = meteor().pauseOnMine(mode).build();
            boolean breaks = mode == PauseMode.BREAK || mode == PauseMode.BOTH;
            boolean places = mode == PauseMode.PLACE || mode == PauseMode.BOTH;
            assertPauses(s, tick(1).mining(), tick(1).mining(), breaks, places, "mining, " + mode);
            assertPauses(s, tick(1), tick(1), false, false, "not mining, " + mode);
        }
    }

    @Test
    @Covers(PAUSE_ON_LAG)
    void pauseOnLagOffIgnoresTheLag() {
        // Line 1158.
        assertPauses(METEOR, tick(1).lagging(), tick(1).lagging(), true, true, "on");
        assertPauses(meteor().pauseOnLag(false).build(), tick(1).lagging(), tick(1).lagging(), false, false, "off");
    }

    @Test
    @Covers(PAUSE_MODULES)
    void pauseModulesPauseWhileOneOfThemIsOn() {
        // Line 1159: any selected module on pauses both; an empty list never does.
        assertFalse(CrystalTick.anyOn(List.<Boolean>of(), on -> on));
        assertFalse(CrystalTick.anyOn(List.of(false), on -> on));
        assertTrue(CrystalTick.anyOn(List.of(true), on -> on));
        assertTrue(CrystalTick.anyOn(List.of(false, true), on -> on));
        assertPauses(METEOR, tick(1).pauseModule(), tick(1).pauseModule(), true, true, "bed-aura on");
        assertPauses(METEOR, tick(1), tick(1), false, false, "none on");
        // Fast-break does not pause (740).
        CrystalBrain b = new CrystalBrain();
        b.preTick(METEOR, tick(1).pauseModule().build());
        assertTrue(b.crystalAdded(METEOR, crystal(1, 8, 1), 20, HANDS).isPresent());
    }

    @Test
    @Covers(PAUSE_HEALTH)
    void pauseHealthTenPausesAtTen() {
        // Line 1161: health plus absorption <= pause-health.
        CrystalSettings ten = meteor().pauseHealth(10).build();
        assertPauses(ten, tick(1).health(10), tick(1).health(10), true, true, "at 10");
        assertPauses(ten, tick(1).health(10.25), tick(1).health(10.25), false, false, "at 10.25");
        assertPauses(METEOR, tick(1).health(10), tick(1).health(10), false, false, "default at 10");
        // Fast-break does not pause (740).
        CrystalBrain b = new CrystalBrain();
        b.preTick(ten, tick(1).health(10).build());
        assertTrue(b.crystalAdded(ten, crystal(1, 8, 1), 10, HANDS).isPresent());
    }

    @Test
    @Covers(PAUSE_HEALTH)
    void pauseHealthZeroNeverPausesALivingPlayer() {
        CrystalSettings zero = meteor().pauseHealth(0).build();
        assertPauses(zero, tick(1).health(1.5), tick(1).health(1.5), false, false, "at 1.5");
    }

    // Safety

    @Test
    @Covers(SELF_BUDGET)
    void selfBudgetOffPlacesWhatTheBudgetRefuses() {
        // Health 9, self 5: 9 - 5 = 4 < Safe's reserve 5. Meteor's rules alone (max-damage 6, anti-suicide) allow it.
        Crystals.Tick t = tick(1).health(9).candidates(spot(1, 6, 5));
        CrystalBrain on = new CrystalBrain();
        assertNothing(on.preTick(level(RiskLevel.SAFE), t.build()));
        assertTrue(on.holding());
        assertEquals(Reason.OVER_RESERVE, on.lastDecision().reason());
        CrystalBrain off = new CrystalBrain();
        Action a = only(off.preTick(METEOR, tick(1).health(9).candidates(spot(1, 6, 5)).build()));
        assertEquals(Reason.BUDGET_OFF, a.decision().reason());
        assertFalse(off.holding());
    }

    @Test
    @Covers(SELF_BUDGET)
    void selfBudgetOffBreaksOurOwnCrystalBelowTheFloor() {
        // P2: our own crystal needs health - I - self >= F with the budget on; off, Meteor's rules only.
        for (CrystalSettings s : List.of(DEFAULTS, METEOR)) {
            CrystalBrain b = new CrystalBrain();
            b.preTick(s, tick(1).health(20).candidates(spot(9, 6, 1)).build());
            b.placed(9, 0);
            // The placement rotated this tick (line 740 then blocks fast-break): the crystal comes after the next.
            b.preTick(s, tick(2).build());
            Optional<Action> fast = b.crystalAdded(s, crystal(1, 9, 8, 5.5), 7, HANDS);
            // 7 - 5.5 = 1.5 < 2: refused with the budget, broken without it.
            assertEquals(s.selfBudget(), fast.isEmpty(), s.toString());
        }
    }

    // risk (R2-5): the level moves the reserve R and nothing else.

    private static CrystalSettings level(RiskLevel risk) {
        return DEFAULTS.toBuilder().risk(risk).build();
    }

    @Test
    @Covers(RISK)
    void eachLevelKeepsItsOwnReserve() {
        assertEquals(RiskLevel.BALANCED, DEFAULTS.risk());
        assertEquals(3.5, DEFAULTS.budgetReserve(), 0.0);
        assertEquals(5.0, level(RiskLevel.SAFE).budgetReserve(), 0.0);
        assertEquals(3.5, level(RiskLevel.BALANCED).budgetReserve(), 0.0);
        assertEquals(SelfBudget.FLOOR, level(RiskLevel.AGGRESSIVE).budgetReserve(), 0.0);
        assertEquals(2.0, level(RiskLevel.AGGRESSIVE).budgetReserve(), 0.0);
        // Only Custom reads the reserve setting; the others ignore it, whatever it says.
        for (double custom : new double[] {2, 3.5, 5, 7.25, 15}) {
            assertEquals(custom, RiskLevel.CUSTOM.reserve(custom), 0.0);
            assertEquals(custom, DEFAULTS.toBuilder().risk(RiskLevel.CUSTOM).reserve(custom).build().budgetReserve(), 0.0);
            assertEquals(5.0, RiskLevel.SAFE.reserve(custom), 0.0);
            assertEquals(3.5, RiskLevel.BALANCED.reserve(custom), 0.0);
            assertEquals(2.0, RiskLevel.AGGRESSIVE.reserve(custom), 0.0);
            assertEquals(3.5, DEFAULTS.toBuilder().reserve(custom).build().budgetReserve(), 0.0,
                "the default level is Balanced; only Custom reads the reserve setting");
        }
        assertThrows(NullPointerException.class, () -> DEFAULTS.toBuilder().risk(null).build());
    }

    @Test
    @Covers(RISK)
    void safePlacesDownToFiveLeft() {
        // Health 10, self 5: 10 - 5 = 5 leaves R 5 exactly; 9.75 leaves 4.75.
        assertPlaces(1, once(level(RiskLevel.SAFE), tick(1).health(10).candidates(spot(1, 6, 5))));
        assertNothing(once(level(RiskLevel.SAFE), tick(1).health(9.75).candidates(spot(1, 6, 5))));
    }

    @Test
    @Covers(RISK)
    void balancedPlacesDownToThreeAndAHalfLeft() {
        // Health 8.5, self 5: 8.5 - 5 = 3.5 leaves R 3.5 exactly, which Safe refuses; 8.25 leaves 3.25.
        assertPlaces(1, once(level(RiskLevel.BALANCED), tick(1).health(8.5).candidates(spot(1, 6, 5))));
        assertNothing(once(level(RiskLevel.BALANCED), tick(1).health(8.25).candidates(spot(1, 6, 5))));
        assertNothing(once(level(RiskLevel.SAFE), tick(1).health(8.5).candidates(spot(1, 6, 5))));
    }

    @Test
    @Covers(RISK)
    void aggressivePlacesDownToTheFloorAndNoFurther() {
        // R = F: health 7, self 5 leaves exactly 2, which Balanced refuses; 1.99 left is refused.
        CrystalBrain b = new CrystalBrain();
        Action a = only(b.preTick(level(RiskLevel.AGGRESSIVE), tick(1).health(7).candidates(spot(1, 6, 5)).build()));
        assertEquals(Kind.PLACE, a.decision().kind());
        assertEquals(Reason.WITHIN_BUDGET, a.decision().reason());
        assertNothing(once(level(RiskLevel.BALANCED), tick(1).health(7).candidates(spot(1, 6, 5))));
        CrystalBrain past = new CrystalBrain();
        assertNothing(past.preTick(level(RiskLevel.AGGRESSIVE), tick(1).health(6.99).candidates(spot(1, 6, 5)).build()));
        assertEquals(Reason.OVER_RESERVE, past.lastDecision().reason());
        assertTrue(past.holding());
        // Under the floor safe mode cannot help either: a harmless spot must still leave F. (pause-health 0,
        // so Meteor's own pause does not decide it.) Health 2.25 with a crystal of self 0.5 standing: 1.75 left.
        CrystalBrain floor = new CrystalBrain();
        CrystalSettings low = level(RiskLevel.AGGRESSIVE).toBuilder().pauseHealth(0).build();
        assertNothing(floor.preTick(low, tick(1).health(2.25).crystals(crystal(2, 1, 0.5)).candidates(spot(1, 6, 0)).build()));
        assertEquals(Reason.BELOW_FLOOR, floor.lastDecision().reason());
        assertPlaces(1, once(low, tick(1).health(2.5).crystals(crystal(2, 1, 0.5)).candidates(spot(1, 6, 0))));
    }

    @Test
    @Covers(RISK)
    void aggressivesOwnCrystalAtTheLimitCanStillBeBroken() {
        // Placed at health 7 with self 5 (2 left); when it appears, breaking it leaves 7 - 0 - 5 = 2 >= F.
        CrystalSettings aggressive = level(RiskLevel.AGGRESSIVE);
        CrystalBrain fast = new CrystalBrain();
        assertPlaces(9, fast.preTick(aggressive, tick(1).health(7).candidates(spot(9, 8, 5)).build()));
        fast.placed(9, 0);
        assertNothing(fast.preTick(aggressive, tick(2).health(7).build()));
        assertEquals(Decision.breakCrystal(1, Reason.WITHIN_BUDGET),
            fast.crystalAdded(aggressive, crystal(1, 9, 8, 5), 7, HANDS).orElseThrow().decision());

        // The same crystal found at the next pre-tick instead (fast-break off).
        CrystalSettings slow = aggressive.toBuilder().fastBreak(false).build();
        CrystalBrain b = new CrystalBrain();
        assertPlaces(9, b.preTick(slow, tick(1).health(7).candidates(spot(9, 8, 5)).build()));
        b.placed(9, 0);
        assertNothing(b.preTick(slow, tick(2).health(7).build()));
        assertTrue(b.crystalAdded(slow, crystal(1, 9, 8, 5), 7, HANDS).isEmpty());
        assertEquals(Decision.breakCrystal(1, Reason.WITHIN_BUDGET),
            only(b.preTick(slow, tick(3).health(7).crystals(crystal(1, 9, 8, 5)).build())).decision());
    }

    @Test
    @Covers({RISK, RESERVE})
    void customUsesTheReserveSettingAndNoOtherLevelDoes() {
        CrystalSettings custom = DEFAULTS.toBuilder().risk(RiskLevel.CUSTOM).reserve(15).build();
        assertPlaces(1, once(custom, tick(1).candidates(spot(1, 6, 5))));
        assertNothing(once(custom, tick(1).candidates(spot(1, 6, 5.25))));
        // The same reserve under any other level is ignored: 20 - 5.25 = 14.75 is placed.
        for (RiskLevel other : List.of(RiskLevel.SAFE, RiskLevel.BALANCED, RiskLevel.AGGRESSIVE)) {
            assertPlaces(1, once(custom.toBuilder().risk(other).build(), tick(1).candidates(spot(1, 6, 5.25))));
        }
        // Custom with the reserve at 2 is Aggressive; with 5 it is Safe.
        CrystalSettings two = custom.toBuilder().reserve(2).build();
        assertPlaces(1, once(two, tick(1).health(7).candidates(spot(1, 6, 5))));
        assertNothing(once(two, tick(1).health(6.99).candidates(spot(1, 6, 5))));
        CrystalSettings five = custom.toBuilder().reserve(5).build();
        assertPlaces(1, once(five, tick(1).health(10).candidates(spot(1, 6, 5))));
        assertNothing(once(five, tick(1).health(9.75).candidates(spot(1, 6, 5))));
    }

    @Test
    @Covers(RISK)
    void aLevelChangedMidFightAppliesFromTheNextDecision() {
        // One brain, one fight: health 8.5 and a spot of self 5, which leaves 3.5.
        CrystalBrain b = new CrystalBrain();
        assertNothing(b.preTick(level(RiskLevel.SAFE), tick(1).health(8.5).candidates(spot(1, 6, 5)).build()));
        assertTrue(b.holding());
        assertPlaces(1, b.preTick(level(RiskLevel.BALANCED), tick(2).health(8.5).candidates(spot(1, 6, 5)).build()));
        assertFalse(b.holding());
        // Back to Safe: the next decision refuses again.
        assertNothing(b.preTick(level(RiskLevel.SAFE), tick(3).health(8.5).candidates(spot(1, 6, 5)).build()));
        assertEquals(Reason.OVER_RESERVE, b.lastDecision().reason());
        // Split phases, as the adapter drives them: the level given to the break phase is the one placing uses.
        CrystalBrain split = new CrystalBrain();
        assertTrue(split.breakPhase(level(RiskLevel.SAFE), tick(1).health(8.5).build()).isEmpty());
        assertTrue(split.placePhase(8.5, List.of(spot(1, 6, 5))).isEmpty());
        assertTrue(split.breakPhase(level(RiskLevel.BALANCED), tick(2).health(8.5).build()).isEmpty());
        assertEquals(Kind.PLACE, split.placePhase(8.5, List.of(spot(1, 6, 5))).orElseThrow().decision().kind());
    }

    @Test
    @Covers(RISK)
    void theLevelNeverMattersWithTheBudgetOff() {
        // Meteor's rules only: health 6.99 and self 5 is placed at every level.
        for (RiskLevel risk : RiskLevel.values()) {
            CrystalSettings off = METEOR.toBuilder().risk(risk).build();
            Action a = only(new CrystalBrain().preTick(off, tick(1).health(6.99).candidates(spot(1, 6, 5)).build()));
            assertEquals(Reason.BUDGET_OFF, a.decision().reason(), risk.toString());
        }
    }

    @Test
    @Covers(RESERVE)
    void reserveAtItsMinimumOfTwoPlacesDownToTwo() {
        // Health 7, self 5: 7 - 5 = 2 leaves the reserve 2 exactly, and not the default 5.
        CrystalSettings two = DEFAULTS.toBuilder().risk(RiskLevel.CUSTOM).reserve(2).build();
        assertPlaces(1, once(two, tick(1).health(7).candidates(spot(1, 6, 5))));
        assertNothing(once(two, tick(1).health(7).candidates(spot(1, 6, 5.25))));
        assertNothing(once(DEFAULTS, tick(1).health(7).candidates(spot(1, 6, 5))));
        assertThrows(IllegalArgumentException.class, () -> DEFAULTS.toBuilder().reserve(Math.nextDown(2.0)).build());
    }

    @Test
    @Covers(RESERVE)
    void aHighReserveKeepsMoreBack() {
        // Health 20, self 5: 20 - 5 = 15 leaves a reserve of 15; 5.25 does not.
        CrystalSettings fifteen = DEFAULTS.toBuilder().risk(RiskLevel.CUSTOM).reserve(15).build();
        assertPlaces(1, once(fifteen, tick(1).candidates(spot(1, 6, 5))));
        assertNothing(once(fifteen, tick(1).candidates(spot(1, 6, 5.25))));
        assertPlaces(1, once(DEFAULTS, tick(1).candidates(spot(1, 6, 5.25))));
    }

    @Test
    @Covers(SAFE_SELF_DAMAGE)
    void safeSelfDamageZeroAllowsOnlyHarmlessSpotsUnderTheReserve() {
        // Health 6.5 with a crystal of self 2 standing (C = 2): a spot of self 0 leaves 4.5, under Safe's reserve
        // 5, so only safe mode can allow it; with safe-self-damage 0 it allows self 0 and not 0.25.
        CrystalSettings zero = level(RiskLevel.SAFE).toBuilder().safeSelfDamage(0).build();
        CrystalSeen standing = crystal(1, 1, 2);
        assertPlaces(2, once(zero, tick(1).health(6.5).crystals(standing).candidates(spot(2, 6, 0))));
        assertNothing(once(zero, tick(1).health(6.5).crystals(standing).candidates(spot(2, 6, 0.25))));
        assertPlaces(2, once(DEFAULTS, tick(1).health(6.5).crystals(standing).candidates(spot(2, 6, 0.25))));
    }

    @Test
    @Covers(SAFE_SELF_DAMAGE)
    void safeSelfDamageTwoAllowsUpToTwoWhileTheFloorHolds() {
        // Health 8, a standing crystal of self 2 (C = 2): a spot of self 2 leaves 4 < Safe's reserve 5 but >= F 2.
        CrystalSettings two = level(RiskLevel.SAFE).toBuilder().safeSelfDamage(2).build();
        CrystalSeen standing = crystal(1, 1, 2);
        assertPlaces(2, once(two, tick(1).health(8).crystals(standing).candidates(spot(2, 6, 2))));
        assertNothing(once(two, tick(1).health(8).crystals(standing).candidates(spot(2, 6, 2.25))));
        assertNothing(once(level(RiskLevel.SAFE), tick(1).health(8).crystals(standing).candidates(spot(2, 6, 2))));
        // Health 6: 6 - 2 - 2 = 2, the floor exactly; 5.75 leaves 1.75 below it.
        assertPlaces(2, once(two, tick(1).health(6).crystals(standing).candidates(spot(2, 6, 2))));
        assertNothing(once(two, tick(1).health(5.75).crystals(standing).candidates(spot(2, 6, 2))));
    }
}
