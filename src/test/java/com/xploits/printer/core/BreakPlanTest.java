package com.xploits.printer.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Printer spec §5.4 and spike S7: vanilla's break delta, the held-mining clock, tool choice, the gap. */
class BreakPlanTest {
    private static final BreakPlan.Hardness STONE = new BreakPlan.Hardness(1.5f, true);
    private static final BreakPlan.Hardness OBSIDIAN = new BreakPlan.Hardness(50.0f, true);
    private static final BreakPlan.Hardness DIRT = new BreakPlan.Hardness(0.5f, false);
    private static final BreakPlan.Body PLAIN = BreakPlan.Body.PLAIN;

    private static BreakPlan.Tool pick(int slot, int efficiency, int durability) {
        return new BreakPlan.Tool(slot, 8.0f, true, efficiency, durability);
    }

    @Test
    void speedFollowsVanillaOpForOp() {
        assertEquals(1.0f, BreakPlan.speed(BreakPlan.Tool.hand(0), PLAIN));
        assertEquals(34.0f, BreakPlan.speed(pick(0, 5, 100), PLAIN));
        assertEquals(8.0f, BreakPlan.speed(pick(0, 0, 100), PLAIN));
        // Efficiency counts only on a tool faster than the hand.
        assertEquals(1.0f, BreakPlan.speed(new BreakPlan.Tool(0, 1.0f, false, 5, -1), PLAIN));
        assertEquals(11.2f, BreakPlan.speed(pick(0, 0, 100), new BreakPlan.Body(1, -1, 1.0f, false, 0.2f, true)));
        assertEquals(0.3f, BreakPlan.speed(BreakPlan.Tool.hand(0), new BreakPlan.Body(-1, 0, 1.0f, false, 0.2f, true)));
        assertEquals(8.1E-4f, BreakPlan.speed(BreakPlan.Tool.hand(0), new BreakPlan.Body(-1, 3, 1.0f, false, 0.2f, true)));
        assertEquals(8.1E-4f, BreakPlan.speed(BreakPlan.Tool.hand(0), new BreakPlan.Body(-1, 7, 1.0f, false, 0.2f, true)));
        assertEquals(1.5f, BreakPlan.speed(BreakPlan.Tool.hand(0), new BreakPlan.Body(-1, -1, 1.5f, false, 0.2f, true)));
        assertEquals(0.2f, BreakPlan.speed(BreakPlan.Tool.hand(0), new BreakPlan.Body(-1, -1, 1.0f, true, 0.2f, true)));
        assertEquals(0.2f, BreakPlan.speed(BreakPlan.Tool.hand(0), new BreakPlan.Body(-1, -1, 1.0f, false, 0.2f, false)));
    }

    @Test
    void deltaDividesByHardnessAndThirtyOrAHundred() {
        assertEquals(0.006666667f, BreakPlan.delta(BreakPlan.Tool.hand(0), PLAIN, STONE));
        assertEquals(0.17777778f, BreakPlan.delta(pick(0, 0, 100), PLAIN, STONE));
        assertEquals(0.06666667f, BreakPlan.delta(BreakPlan.Tool.hand(0), PLAIN, DIRT));
        assertEquals(0.022666667f, BreakPlan.delta(pick(0, 5, 100), PLAIN, OBSIDIAN));
        assertEquals(0f, BreakPlan.delta(pick(0, 5, 100), PLAIN, new BreakPlan.Hardness(-1f, false)));
        assertTrue(BreakPlan.delta(BreakPlan.Tool.hand(0), PLAIN, new BreakPlan.Hardness(0f, false)) >= 1f, "instant");
    }

    @Test
    void ticksAccumulateInFloatLikeTheHeldMiningClock() {
        assertEquals(151, BreakPlan.ticks(0.006666667f), "stone by hand: float accumulation, not ceil(1/delta)");
        assertEquals(13, BreakPlan.ticks(1.0f / 12.0f));
        assertEquals(4, BreakPlan.ticks(0.25f));
        assertEquals(45, BreakPlan.ticks(0.022666667f));
        assertEquals(188, BreakPlan.ticks(0.0053333333f));
        assertEquals(15, BreakPlan.ticks(0.06666667f));
        assertEquals(2, BreakPlan.ticks(0.53333336f));
        assertEquals(5, BreakPlan.ticks(0.2488889f));
        assertEquals(51, BreakPlan.ticks(0.020000001f));
        assertEquals(76, BreakPlan.ticks(0.013333334f));
        assertEquals(0, BreakPlan.ticks(1.0f), "broken on START");
        assertEquals(0, BreakPlan.ticks(2.5f));
        assertEquals(Integer.MAX_VALUE, BreakPlan.ticks(0f));
        assertEquals(Integer.MAX_VALUE, BreakPlan.ticks(Float.NaN));
        assertEquals(Integer.MAX_VALUE, BreakPlan.ticks(1e-9f), "a sum that stops growing never reaches 1");
    }

    @Test
    void theFastestUsableToolWinsWithinTheCap() {
        List<BreakPlan.Tool> hotbar = List.of(BreakPlan.Tool.hand(0), pick(1, 0, 1000),
            new BreakPlan.Tool(2, 1.0f, false, 0, 500));
        assertEquals(Optional.of(new BreakPlan.Choice(1, 0.17777778f, 6)),
            BreakPlan.choose(hotbar, 0, PLAIN, STONE, PrinterLimits.DEFAULTS));
    }

    @Test
    void obsidianQualifiesOnlyWithAnEfficientPickaxe() {
        assertEquals(Optional.empty(), BreakPlan.choose(List.of(pick(3, 0, 1000)), 0, PLAIN, OBSIDIAN, PrinterLimits.DEFAULTS));
        assertEquals(Optional.of(new BreakPlan.Choice(3, 0.022666667f, 45)),
            BreakPlan.choose(List.of(pick(3, 5, 1000)), 0, PLAIN, OBSIDIAN, PrinterLimits.DEFAULTS));
    }

    @Test
    void aToolUnderTheDurabilityFloorIsNeverUsed() {
        assertEquals(Optional.empty(), BreakPlan.choose(List.of(pick(3, 5, 9)), 0, PLAIN, OBSIDIAN, PrinterLimits.DEFAULTS));
        assertEquals(3, BreakPlan.choose(List.of(pick(3, 5, 10)), 0, PLAIN, OBSIDIAN, PrinterLimits.DEFAULTS).orElseThrow().slot());
    }

    @Test
    void aTiePrefersTheSelectedSlotThenTheLowest() {
        BreakPlan.Tool shovel2 = new BreakPlan.Tool(2, 8.0f, true, 0, 1000);
        BreakPlan.Tool shovel5 = new BreakPlan.Tool(5, 8.0f, true, 0, 1000);
        assertEquals(5, BreakPlan.choose(List.of(shovel2, shovel5), 5, PLAIN, DIRT, PrinterLimits.DEFAULTS).orElseThrow().slot());
        assertEquals(2, BreakPlan.choose(List.of(shovel5, shovel2), 0, PLAIN, DIRT, PrinterLimits.DEFAULTS).orElseThrow().slot());
    }

    @Test
    void theClockStopsOnTheFirstTickTheProgressReachesOne() {
        BreakPlan.Clock clock = new BreakPlan.Clock();
        assertEquals(BreakPlan.Step.CONTINUE, clock.tick(0.25f));
        assertEquals(BreakPlan.Step.CONTINUE, clock.tick(0.25f));
        assertEquals(BreakPlan.Step.CONTINUE, clock.tick(0.25f));
        assertEquals(BreakPlan.Step.STOP, clock.tick(0.25f));
        assertEquals(4, clock.ticks());
        assertThrows(IllegalStateException.class, () -> clock.tick(0.25f));
    }

    @Test
    void theDeltaIsReadEveryTick() {
        BreakPlan.Clock clock = new BreakPlan.Clock();
        assertEquals(BreakPlan.Step.CONTINUE, clock.tick(0.25f));
        assertEquals(BreakPlan.Step.CONTINUE, clock.tick(0.25f));
        assertEquals(BreakPlan.Step.STOP, clock.tick(0.5f));
    }

    @Test
    void anInstantBlockIsStartOnly() {
        assertTrue(BreakPlan.Clock.instant(1.0f));
        assertFalse(BreakPlan.Clock.instant(0.99f));
    }

    @Test
    void theNextStartWaitsSixTicksAfterTheLastEnd() {
        assertTrue(BreakPlan.mayStart(5, -1, PrinterLimits.DEFAULTS));
        assertFalse(BreakPlan.mayStart(15, 10, PrinterLimits.DEFAULTS));
        assertTrue(BreakPlan.mayStart(16, 10, PrinterLimits.DEFAULTS));
    }
}
