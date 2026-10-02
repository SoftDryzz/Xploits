package com.xploits.printer.core;

import java.util.List;
import java.util.Optional;

/**
 * Mining one wrong block like vanilla (printer spec §5.4, spike S7): the break delta reproduced op for op in float, the
 * held-mining clock (START at progress 0, {@code progress += delta} every later tick, STOP on the first tick it reaches 1),
 * the choice of hotbar tool and the gap before the next START. The adapter feeds the facts of each hotbar slot and of the
 * player; it compares this delta with vanilla's own for the selected tool every tick and stops on a mismatch.
 */
public final class BreakPlan {
    private BreakPlan() {
    }

    /** No sum may run longer than this; past it the block never breaks. */
    private static final int MAX_TICKS = 100_000;

    /**
     * One hotbar slot as a tool for one block.
     *
     * @param multiplier     {@code ItemStack.getMiningSpeedMultiplier(state)}: 1 for the hand or a non-tool
     * @param suitable       {@code ItemStack.isSuitableFor(state)}
     * @param efficiency     the Efficiency level, 0 for none
     * @param durabilityLeft uses left, -1 when the item is not damageable
     */
    public record Tool(int slot, float multiplier, boolean suitable, int efficiency, int durabilityLeft) {
        public static Tool hand(int slot) {
            return new Tool(slot, 1.0f, false, 0, -1);
        }
    }

    /**
     * The player's side of the formula.
     *
     * @param haste          the larger of the Haste and Conduit Power amplifiers, -1 for neither
     * @param fatigue        the Mining Fatigue amplifier, -1 for none
     * @param breakSpeed     the {@code BLOCK_BREAK_SPEED} attribute (1 by default)
     * @param submergedSpeed the {@code SUBMERGED_MINING_SPEED} attribute (0.2 by default), used under water only
     */
    public record Body(int haste, int fatigue, float breakSpeed, boolean submerged, float submergedSpeed,
                       boolean onGround) {
        public static final Body PLAIN = new Body(-1, -1, 1.0f, false, 0.2f, true);
    }

    /** The block's hardness (negative: unbreakable) and whether its drops need the right tool. */
    public record Hardness(float value, boolean toolRequired) {
    }

    /** The tool to mine with: its slot, the delta it gives and the held-mining ticks from START to STOP. */
    public record Choice(int slot, float delta, int ticks) {
    }

    public enum Step { CONTINUE, STOP }

    /** {@code PlayerEntity.getBlockBreakingSpeed} with this tool in hand. */
    public static float speed(Tool tool, Body body) {
        float f = tool.multiplier();
        if (f > 1.0f && tool.efficiency() > 0) f += (float) (tool.efficiency() * tool.efficiency() + 1);
        if (body.haste() >= 0) f *= 1.0f + (float) (body.haste() + 1) * 0.2f;
        if (body.fatigue() >= 0) {
            float fatigue = switch (body.fatigue()) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1E-4f;
            };
            f *= fatigue;
        }
        f *= body.breakSpeed();
        if (body.submerged()) f *= body.submergedSpeed();
        if (!body.onGround()) f /= 5.0f;
        return f;
    }

    /** {@code AbstractBlock.calcBlockBreakingDelta} with this tool in hand; 0 for an unbreakable block. */
    public static float delta(Tool tool, Body body, Hardness hardness) {
        if (hardness.value() < 0) return 0f;
        boolean canHarvest = !hardness.toolRequired() || tool.suitable();
        int divisor = canHarvest ? 30 : 100;
        return speed(tool, body) / hardness.value() / (float) divisor;
    }

    /** Ticks from START to STOP on the held-mining clock; 0 when START alone breaks it; MAX_VALUE when it never does. */
    public static int ticks(float delta) {
        if (Float.isNaN(delta) || delta <= 0f) return Integer.MAX_VALUE;
        if (delta >= 1.0f) return 0;
        float progress = 0f;
        int n = 0;
        while (progress < 1.0f) {
            float next = progress + delta;
            if (next == progress || n >= MAX_TICKS) return Integer.MAX_VALUE;
            progress = next;
            n++;
        }
        return n;
    }

    /**
     * The fastest hotbar tool not under the durability floor whose ticks stay within the cap; a tie goes to the selected
     * slot, then to the lowest. Empty: no tool qualifies (the block is skipped and reported, §5.4).
     */
    public static Optional<Choice> choose(List<Tool> hotbar, int selected, Body body, Hardness hardness,
                                          PrinterLimits limits) {
        Choice best = null;
        for (Tool tool : hotbar) {
            if (tool.durabilityLeft() >= 0 && tool.durabilityLeft() < limits.durabilityFloor()) continue;
            float d = delta(tool, body, hardness);
            int t = ticks(d);
            if (t > limits.breakCapTicks()) continue;
            Choice c = new Choice(tool.slot(), d, t);
            if (best == null || better(c, best, selected)) best = c;
        }
        return Optional.ofNullable(best);
    }

    private static boolean better(Choice c, Choice best, int selected) {
        if (c.ticks() != best.ticks()) return c.ticks() < best.ticks();
        if (best.slot() == selected) return false;
        if (c.slot() == selected) return true;
        return c.slot() < best.slot();
    }

    /** The next START waits {@code breakGapTicks} after the last STOP or ABORT (vanilla's cooldown of 5, plus the tick). */
    public static boolean mayStart(long now, long lastEnd, PrinterLimits limits) {
        return lastEnd < 0 || now - lastEnd >= limits.breakGapTicks();
    }

    /** One dig, from START. */
    public static final class Clock {
        private float progress;
        private int ticks;
        private boolean done;

        /** A delta of 1 or more at START: the server breaks it on START, no STOP follows. */
        public static boolean instant(float delta) {
            return delta >= 1.0f;
        }

        /** One tick after START, with that tick's delta. */
        public Step tick(float delta) {
            if (done) throw new IllegalStateException("this block is already broken");
            progress += delta;
            ticks++;
            if (progress >= 1.0f) {
                done = true;
                return Step.STOP;
            }
            return Step.CONTINUE;
        }

        public int ticks() {
            return ticks;
        }
    }
}
