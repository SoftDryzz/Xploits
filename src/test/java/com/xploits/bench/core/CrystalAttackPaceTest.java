package com.xploits.bench.core;

import com.xploits.bench.core.CrystalAttackPace.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task A2 requirement 1: the crystal attack's cadence (spawn every 10 ticks starting at 20, hit 2 ticks after
 * each spawn), its stop condition (either combatant dead), and its cell choice (highest raw damage, ties keep
 * the first). Pure and deterministic.
 */
class CrystalAttackPaceTest {
    // --- Pacing -------------------------------------------------------------------------------------

    @Test
    void nothingBeforeTheFirstAttack() {
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(Integer.MIN_VALUE, true, true));
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(-1, true, true));
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(0, true, true));
        for (int t = 0; t < CrystalAttackPace.FIRST_ATTACK; t++) {
            assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(t, true, true), "tick " + t);
        }
    }

    @Test
    void spawnsExactlyAtTheFirstAttackTick() {
        assertEquals(Phase.SPAWN, CrystalAttackPace.phaseAt(CrystalAttackPace.FIRST_ATTACK, true, true));
    }

    @Test
    void hitsExactlyTheDelayAfterTheSpawn() {
        int hitTick = CrystalAttackPace.FIRST_ATTACK + CrystalAttackPace.ATTACK_DELAY;
        assertEquals(Phase.HIT, CrystalAttackPace.phaseAt(hitTick, true, true));
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(hitTick - 1, true, true));
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(hitTick + 1, true, true));
    }

    @Test
    void theWholeFirstCycleIsNoneExceptSpawnAndHit() {
        for (int offset = 0; offset < CrystalAttackPace.ATTACK_EVERY; offset++) {
            int t = CrystalAttackPace.FIRST_ATTACK + offset;
            Phase expected = offset == 0 ? Phase.SPAWN : offset == CrystalAttackPace.ATTACK_DELAY ? Phase.HIT : Phase.NONE;
            assertEquals(expected, CrystalAttackPace.phaseAt(t, true, true), "offset " + offset);
        }
    }

    @Test
    void repeatsEveryAttackEveryTicks() {
        for (int cycle = 0; cycle < 5; cycle++) {
            int spawnTick = CrystalAttackPace.FIRST_ATTACK + cycle * CrystalAttackPace.ATTACK_EVERY;
            int hitTick = spawnTick + CrystalAttackPace.ATTACK_DELAY;
            assertEquals(Phase.SPAWN, CrystalAttackPace.phaseAt(spawnTick, true, true), "cycle " + cycle);
            assertEquals(Phase.HIT, CrystalAttackPace.phaseAt(hitTick, true, true), "cycle " + cycle);
        }
    }

    @Test
    void theConstantsAreTheBriefsExactValues() {
        assertEquals(20, CrystalAttackPace.FIRST_ATTACK);
        assertEquals(10, CrystalAttackPace.ATTACK_EVERY);
        assertEquals(2, CrystalAttackPace.ATTACK_DELAY);
    }

    // --- Stop condition -------------------------------------------------------------------------------

    @Test
    void nothingOnceTheSparringIsDeadEvenOnASpawnTick() {
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(CrystalAttackPace.FIRST_ATTACK, false, true));
    }

    @Test
    void nothingOnceTheTargetIsDeadEvenOnASpawnTick() {
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(CrystalAttackPace.FIRST_ATTACK, true, false));
    }

    @Test
    void nothingOnceEitherIsDeadEvenOnAHitTick() {
        int hitTick = CrystalAttackPace.FIRST_ATTACK + CrystalAttackPace.ATTACK_DELAY;
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(hitTick, false, true));
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(hitTick, true, false));
        assertEquals(Phase.NONE, CrystalAttackPace.phaseAt(hitTick, false, false));
    }

    // --- Abandon (fix round 1) -------------------------------------------------------------------------

    @Test
    void nothingIsAbandonedWhileBothAreAlive() {
        assertEquals(false, CrystalAttackPace.shouldAbandon(true, true));
    }

    @Test
    void abandonWhenTheSparringIsDead() {
        assertEquals(true, CrystalAttackPace.shouldAbandon(false, true));
    }

    @Test
    void abandonWhenTheTargetIsDead() {
        assertEquals(true, CrystalAttackPace.shouldAbandon(true, false));
    }

    @Test
    void abandonWhenBothAreDead() {
        assertEquals(true, CrystalAttackPace.shouldAbandon(false, false));
    }

    // --- Cell choice ----------------------------------------------------------------------------------

    @Test
    void oneCandidateIsAlwaysChosen() {
        assertEquals(0, CrystalAttackPace.chooseCell(List.of(3.5)));
    }

    @Test
    void theHighestDamageWins() {
        assertEquals(2, CrystalAttackPace.chooseCell(List.of(1.0, 5.0, 9.0, 4.0)));
    }

    @Test
    void aTieKeepsTheFirstInTheList() {
        assertEquals(0, CrystalAttackPace.chooseCell(List.of(9.0, 9.0, 3.0)));
        assertEquals(1, CrystalAttackPace.chooseCell(List.of(1.0, 9.0, 9.0, 2.0)));
    }

    @Test
    void anEmptyListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CrystalAttackPace.chooseCell(List.of()));
    }

    @Test
    void negativeValuesStillPickTheHighest() {
        assertEquals(1, CrystalAttackPace.chooseCell(List.of(-5.0, -2.0, -9.0)));
    }

    @Test
    void zeroDamageEverywhereStillPicksTheFirst() {
        assertEquals(0, CrystalAttackPace.chooseCell(List.of(0.0, 0.0, 0.0)));
    }
}
