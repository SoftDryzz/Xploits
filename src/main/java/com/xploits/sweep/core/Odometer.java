package com.xploits.sweep.core;

/**
 * The flight odometer: it adds up what the player <b>has really flown</b>, tick by tick, and skips
 * the jumps that were not flown.
 *
 * <p><b>Why just adding the distance between two ticks will not do.</b> A teleport -a portal, a
 * {@code /tpa}, a respawn, a server rubber-band- puts hundreds or thousands of blocks between one
 * tick and the next in one go. That stretch was not flown and did not cost a single firework, but it
 * is added to the blocks flown, and that is where the number feeding {@link FuelBudget} comes from:
 * the measured spending ends up <b>divided by more blocks than were flown</b>, that is more blocks
 * per firework than the real ones.
 *
 * <p>And that direction of the error is exactly the dangerous one, not the cautious one. With an
 * inflated rate, {@link FuelBudget#willRunOut} projects that the fireworks go further than they do
 * and answers that they will last when they will not: it is exactly the rounding that the javadoc of
 * {@code FuelBudget} says this part of the module never makes -"faced with two possible roundings,
 * the one that overestimates the risk is always picked"- slipping in through the distance door
 * instead of the spending one. The price is being stranded far from home, which is what the firework
 * protection exists to prevent.
 *
 * <p><b>How a jump is told apart from fast flight:</b> by the speed, which within a tick is a
 * physical bound. See {@link #MAX_BLOCKS_PER_TICK}. The discarded stretch is neither added nor
 * spread: it was not flown, so it does not exist for the budget.
 *
 * <p>The last step is kept apart because it serves another purpose: it is the speed with which
 * {@link WidthProbe#sample} decides whether a width sample is contaminated by drift. There <b>the raw
 * step</b> is handed over, unfiltered, and on purpose: a tick with a teleport inside is the worst
 * possible moment to measure the server's reach, so the probe should see it as large and discard
 * the sample.
 *
 * <p>This class does not touch Minecraft or Meteor: it receives distances already measured.
 */
public final class Odometer {
    /**
     * From how many blocks in a single tick it is taken for granted that the player did not fly that
     * stretch, and where the number comes from.
     *
     * <p>From below it has to let any real flight through: an elytra pushed by fireworks goes at
     * about 33 blocks per second -1.65 per tick- and a dive with chained fireworks does not exceed
     * about 60 -3 per tick-. Ten blocks per tick are 200 per second, three times the fastest
     * sustainable flight: no flown tick gets there.
     *
     * <p>From above it has to catch what really matters. A useful teleport moves hundreds or
     * thousands of blocks; the shortest this module can run into is a Nether portal, and even that
     * one changes dimension and coordinates in one go. A jump of fewer than ten blocks that slipped
     * in does not change a rate measured over stretches of a thousand.
     */
    public static final double MAX_BLOCKS_PER_TICK = 10;

    private double blocksFlown;
    private double lastStep;
    private int jumps;

    /**
     * Records how far the player has moved in this tick.
     *
     * @param blocks distance covered since the previous tick, in blocks; never negative
     * @return whether the step was counted as flight. {@code false} means it was discarded as a jump
     * @throws IllegalArgumentException if {@code blocks} is negative or {@code NaN}: it is not a
     *                                  distance, and letting it in would corrupt the total the
     *                                  firework projection comes from
     */
    public boolean advance(double blocks) {
        // Negated so that a NaN falls here instead of slipping through: compared with anything it
        // gives false, so a "blocks >= 0" would let it pass and then poison the whole total.
        if (!(blocks >= 0)) {
            throw new IllegalArgumentException(
                "a tick's movement must be zero or positive (got " + blocks
                    + "): it is not a distance, and adding it would corrupt the blocks flown that"
                    + " the firework projection comes from");
        }

        lastStep = blocks;
        if (blocks > MAX_BLOCKS_PER_TICK) {
            jumps++;
            return false;
        }
        blocksFlown += blocks;
        return true;
    }

    /** The blocks really flown since the odometer was armed. */
    public double blocksFlown() {
        return blocksFlown;
    }

    /**
     * The last recorded movement, <b>raw</b>: without filtering out jumps. It is the player's speed
     * in the last tick, which is what {@link WidthProbe#sample} needs to know whether a width sample
     * comes in inflated by drift -see the class javadoc-.
     */
    public double lastStep() {
        return lastStep;
    }

    /** How many steps have been discarded as jumps and not flight, so that it can be said. */
    public int jumps() {
        return jumps;
    }
}
