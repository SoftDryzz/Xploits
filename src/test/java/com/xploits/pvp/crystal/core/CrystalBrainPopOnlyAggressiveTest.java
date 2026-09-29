package com.xploits.pvp.crystal.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.xploits.pvp.crystal.core.CrystalBrainFinishingBlowTest.LOW_MIN_DAMAGE;
import static com.xploits.pvp.crystal.core.CrystalBrainFinishingBlowTest.assertDecision;
import static com.xploits.pvp.crystal.core.CrystalBrainFinishingBlowTest.trustedEnemy;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertNothing;
import static com.xploits.pvp.crystal.core.CrystalBrainParityTest.assertPlaces;
import static com.xploits.pvp.crystal.core.Crystals.DEFAULTS;
import static com.xploits.pvp.crystal.core.Crystals.ENEMY;
import static com.xploits.pvp.crystal.core.Crystals.HANDS;
import static com.xploits.pvp.crystal.core.Crystals.crystal;
import static com.xploits.pvp.crystal.core.Crystals.playerWithHands;
import static com.xploits.pvp.crystal.core.Crystals.spot;
import static com.xploits.pvp.crystal.core.Crystals.tick;
import static com.xploits.pvp.crystal.core.Crystals.withTotem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner's decision 2026-09-29: the pop-grade finishing override (popping the target's totem without killing him)
 * may take us below the reserve only at Aggressive; Safe, Balanced and Custom leave a pop-grade crystal to the
 * normal budget. The kill-grade override is unchanged at every level. Health 4 is under pause-health (5), so the
 * ordinary budget is shut and only an override may act; health 6.5 is above it.
 */
class CrystalBrainPopOnlyAggressiveTest {
    private static final TargetView POP = playerWithHands(ENEMY, 3, 4, true, true);
    private static final TargetView KILL = playerWithHands(ENEMY, 3, 4, false, true);
    private static final CrystalTick.Hands TOTEM = withTotem(HANDS, true);

    private static CrystalSettings at(RiskLevel risk) {
        return LOW_MIN_DAMAGE.toBuilder().risk(risk).build();
    }

    private static java.util.List<Action> placeAt(CrystalSettings s, double health, TargetView foe, double self) {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        return b.preTick(s, tick(t).health(health).hands(TOTEM).targets(foe)
            .candidates(spot(3000L, Map.of(ENEMY, 6.0), self)).build());
    }

    private static java.util.List<Action> breakAt(CrystalSettings s, double health, TargetView foe, double self) {
        CrystalBrain b = new CrystalBrain();
        long t = trustedEnemy(b, 1);
        assertPlaces(3000L, b.preTick(DEFAULTS, tick(t).candidates(spot(3000L, Map.of(ENEMY, 6.0), 0)).build()));
        b.placed(3000L, 0);
        CrystalSettings noFastBreak = DEFAULTS.toBuilder().fastBreak(false).build();
        assertTrue(b.crystalAdded(noFastBreak, crystal(950, 3000L, 6.0, 0.0), 20, HANDS).isEmpty());
        return b.preTick(s, tick(t + 1).health(health).hands(TOTEM).targets(foe)
            .crystals(crystal(950, 3000L, 6.0, self)).build());
    }

    @Test
    void underPauseHealthOnlyAggressivePlacesAPopGradeSpotBelowTheReserveDownToTheFloor() {
        // 4 - 2 = 2 = FLOOR.
        for (RiskLevel level : new RiskLevel[] {RiskLevel.SAFE, RiskLevel.BALANCED, RiskLevel.CUSTOM}) {
            assertNothing(placeAt(at(level), 4, POP, 2));
        }
        assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(at(RiskLevel.AGGRESSIVE), 4, POP, 2));
        assertNothing(placeAt(at(RiskLevel.AGGRESSIVE), 4, POP, Math.nextUp(2.0)));
    }

    @Test
    void underPauseHealthOnlyAggressiveBreaksAPopGradeCrystalBelowTheReserve() {
        for (RiskLevel level : new RiskLevel[] {RiskLevel.SAFE, RiskLevel.BALANCED, RiskLevel.CUSTOM}) {
            assertNothing(breakAt(at(level), 4, POP, 2));
        }
        assertDecision(Decision.breakCrystal(950, Reason.FINISHING_BLOW), breakAt(at(RiskLevel.AGGRESSIVE), 4, POP, 2));
        assertNothing(breakAt(at(RiskLevel.AGGRESSIVE), 4, POP, Math.nextUp(2.0)));
    }

    @Test
    void aboveThePauseSafeAndBalancedFollowTheirReserveForAPopGradeSpot() {
        // 6.5 - 4.5 = 2: under Safe's reserve (5) and Balanced's (3.5), at Aggressive's (2).
        assertNothing(placeAt(at(RiskLevel.SAFE), 6.5, POP, 4.5));
        assertNothing(placeAt(at(RiskLevel.BALANCED), 6.5, POP, 4.5));
        assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), placeAt(at(RiskLevel.AGGRESSIVE), 6.5, POP, 4.5));
        // A pop-grade spot the reserve allows is still placed, by the normal budget: Balanced, 6.5 - 3 = 3.5.
        assertDecision(Decision.place(3000L, Reason.WITHIN_BUDGET), placeAt(at(RiskLevel.BALANCED), 6.5, POP, 3));
    }

    @Test
    void theKillGradeOverrideWorksAtEveryLevelIncludingSafe() {
        for (RiskLevel level : RiskLevel.values()) {
            assertDecision(Decision.place(3000L, Reason.FINISHING_BLOW), placeAt(at(level), 4, KILL, 100));
            assertEquals(Decision.breakCrystal(950, Reason.FINISHING_BLOW), only(breakAt(at(level), 4, KILL, 100)).decision());
        }
    }

    private static Action only(java.util.List<Action> actions) {
        assertEquals(1, actions.size(), actions.toString());
        return actions.get(0);
    }
}
