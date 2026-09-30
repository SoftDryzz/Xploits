package com.xploits.pvp.shell.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** surround++'s decision for one tick (spec §4-§7). */
class ShellBrainTest {
    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    private static final ShellBrain.Motion STILL = ShellBrain.Motion.still();
    private static final ShellBrain.Motion POPPED = new ShellBrain.Motion(false, 0, Optional.empty(), true);
    private static final ShellBrain.Motion KEYS = new ShellBrain.Motion(true, 0, Optional.empty(), false);

    /** A hole one block down at (x,-1,z), obsidian walls. */
    private static ShellSnapshot.Builder hole(ShellSnapshot.Builder s, int x, int z) {
        return s.block(x, -1, z, BlockKind.AIR).block(x, -2, z, BlockKind.OTHER).block(x + 1, -1, z, BlockKind.OBSIDIAN)
            .block(x - 1, -1, z, BlockKind.OBSIDIAN).block(x, -1, z + 1, BlockKind.OBSIDIAN).block(x, -1, z - 1, BlockKind.OBSIDIAN);
    }

    /** Us in a 1x1 obsidian hole at feet level on a deepslate floor. */
    private static ShellSnapshot.Builder inOurHole() {
        return Scenes.open().block(1, 0, 0, BlockKind.OBSIDIAN).block(-1, 0, 0, BlockKind.OBSIDIAN)
            .block(0, 0, 1, BlockKind.OBSIDIAN).block(0, 0, -1, BlockKind.OBSIDIAN);
    }

    @Test
    void aPopHoldsTheShellClosedForFiveSeconds() {
        ShellBrain brain = new ShellBrain();
        ShellSnapshot calm = Scenes.open().build();
        FakeOracle nothing = new FakeOracle();
        assertTrue(brain.tick(calm, nothing, STILL).placements().isEmpty(), "nothing threatens: nothing is built");
        ShellTick popped = brain.tick(calm, nothing, POPPED);
        assertFalse(popped.placements().isEmpty());
        assertTrue(popped.placements().stream().allMatch(p -> p.reason() == ShellReason.CLOSURE));
        for (int i = 1; i < ShellBrain.HOLD_TICKS; i++) {
            assertFalse(brain.tick(calm, nothing, STILL).placements().isEmpty(), "tick " + i + " of the hold");
        }
        assertTrue(brain.tick(calm, nothing, STILL).placements().isEmpty(), "the hold is over");
    }

    @Test
    void whileWalkingToAHoleNothingIsBuilt() {
        ShellBrain brain = new ShellBrain();
        // Threatened in the open: (-1,1,0) needs its base and deals 10 (weighs 5, over the 4 that makes us move).
        ShellSnapshot s = hole(Scenes.open(), 2, 0).build();
        FakeOracle oracle = new FakeOracle().at(-1, 1, 0, 10);
        ShellTick first = brain.tick(s, oracle, STILL);
        assertEquals(Optional.of(c(2, -1, 0)), first.walkTo());
        assertEquals(Set.of(HoleWalk.Key.LEFT), first.keys(), "facing south, the hole two blocks east is to the left");
        assertTrue(first.placements().isEmpty());
        ShellTick second = brain.tick(s, oracle, new ShellBrain.Motion(false, 0, Optional.of(c(2, -1, 0)), false));
        assertEquals(Set.of(HoleWalk.Key.LEFT), second.keys());
        assertTrue(second.placements().isEmpty());
        assertFalse(second.walkEnded());
    }

    @Test
    void aLostWalkTargetEndsTheWalk() {
        ShellBrain brain = new ShellBrain();
        ShellSnapshot s = hole(Scenes.open(), 2, 0).build();
        FakeOracle oracle = new FakeOracle().at(-1, 1, 0, 10);
        brain.tick(s, oracle, STILL);
        ShellTick lost = brain.tick(s, oracle, STILL);
        assertTrue(lost.walkEnded());
        assertTrue(lost.keys().isEmpty());
        assertFalse(brain.walking());
    }

    @Test
    void yourKeysEndTheWalkAndItWaitsBeforeTheNextOne() {
        ShellBrain brain = new ShellBrain();
        ShellSnapshot s = hole(Scenes.open(), 2, 0).build();
        FakeOracle oracle = new FakeOracle().at(-1, 1, 0, 10);
        brain.tick(s, oracle, STILL);
        assertTrue(brain.tick(s, oracle, new ShellBrain.Motion(true, 0, Optional.of(c(2, -1, 0)), false)).walkEnded());
        ShellTick after = brain.tick(s, oracle, STILL);
        assertEquals(Optional.empty(), after.walkTo(), "it waits before choosing a hole again");
        assertFalse(after.placements().isEmpty(), "and meanwhile it builds");
    }

    @Test
    void itCentresOnlyWhenYouStickOutAndSomethingIsLeftOpen() {
        // No blocks to place, so (-1,0,0), dealing 10 on an obsidian floor, stays open.
        ShellSnapshot out = Scenes.obsidianFloor().feet(new Vec(0.9, 0, 0.5)).obsidian(0).cryingObsidian(0).build();
        FakeOracle oracle = new FakeOracle().at(-1, 0, 0, 10);
        ShellBrain brain = new ShellBrain();
        assertTrue(brain.tick(out, oracle, STILL).centre());
        assertFalse(brain.tick(out, oracle, STILL).centre(), "never two ticks running: at most once a second");
        assertFalse(new ShellBrain().tick(out, oracle, KEYS).centre(), "never while you press a key");
        ShellSnapshot centred = Scenes.obsidianFloor().obsidian(0).cryingObsidian(0).build();
        assertFalse(new ShellBrain().tick(centred, oracle, STILL).centre(), "never when your box is inside its block");
        ShellSnapshot nothingOpen = Scenes.obsidianFloor().feet(new Vec(0.9, 0, 0.5)).build();
        assertFalse(new ShellBrain().tick(nothingOpen, new FakeOracle(), STILL).centre(), "never with nothing left open");
    }

    @Test
    void burrowWhenInAHoleAndACrystalAtYourHeadCannotBeBlocked() {
        // In our hole, no blocks left; (1,1,0) on top of our east wall deals 8.
        ShellSettings burrowOn = new ShellSettings(2, true, true, true, true, true, 6);
        ShellSnapshot s = inOurHole().obsidian(0).cryingObsidian(0).settings(burrowOn).build();
        FakeOracle oracle = new FakeOracle().at(1, 1, 0, 8);
        ShellBrain brain = new ShellBrain();
        assertTrue(brain.tick(s, oracle, STILL).burrow());
        assertFalse(brain.tick(s, oracle, STILL).burrow(), "once; then it waits");
        ShellSnapshot off = inOurHole().obsidian(0).cryingObsidian(0).build();
        assertFalse(new ShellBrain().tick(off, oracle, STILL).burrow(), "off by default");
    }

    @Test
    void theStatusSaysWhetherTheHeadIsCoveredWhatIsLeftAndWhatIsBeingMined() {
        ShellSnapshot enclosed = inOurHole().block(1, 1, 0, BlockKind.OBSIDIAN).block(-1, 1, 0, BlockKind.OBSIDIAN)
            .block(0, 1, 1, BlockKind.OBSIDIAN).block(0, 1, -1, BlockKind.OBSIDIAN).mining(c(1, 0, 0), 5)
            .mining(c(2, 0, 0), 3).build();
        // (1,0,0) is our obsidian wall being mined; (2,0,0) is air, and mining air is no attack on us.
        assertEquals(new ShellStatus(true, 0, 1), new ShellBrain().tick(enclosed, new FakeOracle(), STILL).status());
        ShellSnapshot bare = Scenes.open().obsidian(0).cryingObsidian(0).build();
        ShellStatus open = new ShellBrain().tick(bare, new FakeOracle().at(1, 1, 0, 12), STILL).status();
        assertFalse(open.headCovered());
        assertEquals(1, open.openThreats());
    }

    @Test
    void theCrystalToBreakIsHandedOver() {
        ShellSnapshot s = Scenes.obsidianFloor().crystal(new StandingCrystal(7, c(1, 0, 0), false)).build();
        assertEquals(Optional.of(7), new ShellBrain().tick(s, new FakeOracle().at(1, 0, 0, 10), STILL).breakCrystal());
    }

    @Test
    void anOpponentsHoleIsFilledWithWhatTheBudgetLeaves() {
        ShellSnapshot calm = hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 3, 0).hostile(new Vec(4.5, 0, 0.5)).build();
        assertEquals(List.of(new Placement(c(3, -1, 0), Material.OBSIDIAN, ShellReason.DENY_HOLE)),
            new ShellBrain().tick(calm, new FakeOracle(), STILL).placements());
        // One block a tick and a spot at your head to cover: the shell first, no hole this tick.
        ShellSettings one = new ShellSettings(1, true, false, true, true, false, 6);
        ShellSnapshot busy = hole(ShellSnapshot.builder().floor(BlockKind.OTHER), 3, 0).hostile(new Vec(4.5, 0, 0.5))
            .settings(one).build();
        assertEquals(List.of(new Placement(c(1, 0, 0), Material.CRYING_OBSIDIAN, ShellReason.BASE)),
            new ShellBrain().tick(busy, new FakeOracle().at(1, 1, 0, 12), STILL).placements());
    }

    @Test
    void anOverrideOfTheAurasSpotIsCounted() {
        ShellSnapshot s = Scenes.obsidianFloor().health(11).auraSpot(c(1, 0, 0)).build();
        ShellBrain brain = new ShellBrain();
        brain.tick(s, new FakeOracle().at(1, 0, 0, 10), STILL);
        assertEquals(1, brain.auraOverrides());
        brain.reset();
        assertEquals(0, brain.auraOverrides());
    }
}
