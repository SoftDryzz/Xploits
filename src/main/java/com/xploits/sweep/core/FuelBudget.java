package com.xploits.sweep.core;

import java.util.OptionalDouble;

/**
 * Measures the real firework spending in flight and projects whether they will last, instead of
 * assuming it (Nether Sweep spec §6). The sweep is decided at two different moments and this class
 * only serves the second:
 *
 * <ul>
 *   <li><b>Before takeoff</b>, the estimate comes from the spending per block measured in earlier
 *       sweeps and kept between sessions -the adapter saves and loads that, not this
 *       class-.</li>
 *   <li><b>During the flight</b>, which is what {@code FuelBudget} does: it measures the real
 *       spending of <i>this</i> flight from the fireworks that have really been burnt, and as soon as
 *       there is real data it rules over the initial assumption (spec §6, last line).</li>
 * </ul>
 *
 * <p><b>Which distance to pass it.</b> {@code blocksRemaining} in {@link #willRunOut} is what the
 * player still has to fly from where they are <b>until they are home</b> again if the budget
 * includes the return, not any stretch of the sweep. In particular it <b>is not</b>
 * {@code SweepPlan.totalBlocks()} as is: that number measures only the sweep -from the start of the
 * first lane to the end of the last, links included- and twice already a "total" that did not
 * include the approach from where the player was has slipped into this very module and ended up
 * feeding this very check of whether to take off. The approach -and the return, when it applies- are
 * the responsibility of whoever builds {@code blocksRemaining}, not of this class: {@code FuelBudget}
 * does not know where the player is or where the plan is, it only measures a spending rate and
 * projects the number it is given.
 *
 * <p><b>Underestimating the spending or the remaining distance is the dangerous side, not the
 * cautious one.</b> Too low a spending per block, or too short a remaining distance, make
 * {@link #willRunOut} say the fireworks will last when they will not, and the player takes off on an
 * hours-long trip badly supplied. Faced with two possible roundings, this class always picks the one
 * that overestimates the risk. For the same reason {@link #willRunOut} rejects a negative reserve: a
 * reserve below zero is not "less margin", it is a threshold that drops below what is needed and
 * produces exactly the lie this class exists not to tell -see the method's javadoc-.
 *
 * <p><b>The measured spending expires if too much flight goes by without confirming it.</b> When the
 * player restocks fireworks often -more often than it samples-, no leg has net fireworks spent again
 * and the accumulated totals stay frozen at the last rate that could be measured, however old it
 * is. With nothing to correct it, {@link #willRunOut} would keep projecting on that old figure as if
 * it were current. {@link #blocksPerRocket()} makes it visible: past an unconfirmed stretch as long as
 * the confirmed one, it is empty again -see its javadoc-, instead of lying with a number nobody knows
 * is still true.
 *
 * <p>This class does not touch Minecraft or Meteor: it receives blocks flown and fireworks left
 * already measured, it does not query the player or the inventory.
 */
public final class FuelBudget {
    private boolean hasReference = false;
    private double referenceBlocks;
    private int referenceFireworks;

    private double accumulatedBlocks = 0.0;
    private int accumulatedFireworks = 0;
    private double unconfirmedBlocksSinceLastUpdate = 0.0;

    /**
     * Records a point of the flight: total blocks flown since the module was armed, and fireworks
     * left right now.
     *
     * <p>The first sample only sets the reference the following ones are measured from; it produces
     * no spending by itself, because a single point is not a difference.
     *
     * <p><b>If the fireworks go up compared with the previous sample</b> -the player restocked
     * mid-flight, for example by moving more fireworks into the inventory from a shulker- <b>the leg
     * is ignored</b>: neither its blocks nor its change in fireworks count towards the accumulated
     * spending, because we do not know how many fireworks were really burnt in that leg mixed with
     * the restock. The sample is still kept as the new reference, so the next leg measures real
     * spending again without dragging the gap along.
     *
     * <p>That same ignored leg also counts as unconfirmed flight for {@link #blocksPerRocket()}: if
     * too many blocks like that pile up in a row, without any leg really bringing the fireworks down
     * again, the measured spending expires -see the class javadoc-.
     *
     * @param blocksFlown  total blocks flown since the measurement started
     * @param rocketsLeft  fireworks left in the inventory at this instant
     */
    public void sample(double blocksFlown, int rocketsLeft) {
        if (!hasReference) {
            referenceBlocks = blocksFlown;
            referenceFireworks = rocketsLeft;
            hasReference = true;
            return;
        }

        double deltaBlocks = blocksFlown - referenceBlocks;
        int deltaFireworks = referenceFireworks - rocketsLeft;
        if (deltaFireworks > 0) {
            accumulatedBlocks += deltaBlocks;
            accumulatedFireworks += deltaFireworks;
            unconfirmedBlocksSinceLastUpdate = 0.0;
        } else {
            unconfirmedBlocksSinceLastUpdate += deltaBlocks;
        }

        referenceBlocks = blocksFlown;
        referenceFireworks = rocketsLeft;
    }

    /**
     * Blocks one firework costs, measured over the legs where some were really spent.
     *
     * <p>Empty if there is no such leg yet -no samples, a single sample, or samples in which the
     * fireworks only went up or stayed the same-, instead of zero or any other made-up number that
     * looks like a measurement: it is the same criterion the spec sets for the advance estimate
     * without earlier sweeps (§6), applied here to the in-flight measurement.
     *
     * <p><b>Also empty if the figure has expired.</b> How much has been flown without any leg
     * confirming real spending is compared with how much it took to confirm the current rate in the
     * first place ({@code accumulatedBlocks}): as soon as the former exceeds the latter, the rate
     * stops counting as trustworthy and this method is empty again, even though the internal totals
     * are still there. It is not a fixed number of blocks because there is none in the spec to
     * justify it, and a fixed one would be either too short for a rate well confirmed with a lot of
     * flight behind it, or too long for a rate that was confirmed only once with little flight.
     * Comparing against its own accumulated evidence scales with it: a rate confirmed over a lot of
     * flight holds a lot of unconfirmed flight before doubting itself; one confirmed over little
     * doubts right away. As soon as there is a leg with real spending again, confidence is restored
     * immediately -the unconfirmed flight counter is set to zero in {@link #sample}-.
     */
    public OptionalDouble blocksPerRocket() {
        if (accumulatedFireworks == 0) {
            return OptionalDouble.empty();
        }
        if (unconfirmedBlocksSinceLastUpdate > accumulatedBlocks) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(accumulatedBlocks / accumulatedFireworks);
    }

    /**
     * Whether, at the measured rate, the fireworks will fall short of covering {@code blocksRemaining}
     * while keeping the reserve.
     *
     * <p>It cuts <b>before</b> reaching zero, not on reaching it (spec §10): the threshold of
     * fireworks needed is inflated by {@code (1 + reserveFraction)}, so with a 20% reserve the warning
     * fires with fireworks still in hand, not at the instant they run out.
     *
     * <p><b>{@code reserveFraction} has to be zero or positive.</b> A negative reserve does not reduce
     * the margin, it inverts it: at a rate of 100 blocks/firework, 5000 blocks ahead and a reserve of
     * -1.0, the threshold comes out as {@code 50 * (1 + (-1.0)) = 0}, so with <b>zero fireworks in
     * hand</b> this method would say there is no need to cut. It is exactly what the class javadoc
     * says cannot happen, so it is rejected instead of returning that lie -the same condition also
     * rules out a {@code NaN}, which would always compare as false and let the same failure in
     * through another door-.
     *
     * @param blocksRemaining what is left to fly to the final destination -see the class javadoc on
     *                        what this distance has to include-
     * @param rocketsLeft     fireworks left right now
     * @param reserveFraction fraction of margin over the fireworks needed, e.g. 0.2 for a 20%
     *                        reserve; it has to be {@code >= 0}
     * @return whether the flight has to be cut now
     * @throws IllegalArgumentException         if {@code reserveFraction} is negative or
     *                                           {@code NaN}
     * @throws java.util.NoSuchElementException if {@link #blocksPerRocket()} is empty -it cannot
     *                                           project without any real measurement, or because it
     *                                           has expired; the caller must check it first
     */
    public boolean willRunOut(double blocksRemaining, int rocketsLeft, double reserveFraction) {
        if (!(reserveFraction >= 0)) {
            throw new IllegalArgumentException(
                "reserveFraction must be >= 0 (got " + reserveFraction + "): a negative"
                    + " reserve lowers the threshold of fireworks needed instead of widening it and inverts"
                    + " this class's safety guarantee -see the method's javadoc-");
        }
        double rate = blocksPerRocket().getAsDouble();
        double fireworksNeeded = blocksRemaining / rate;
        double threshold = fireworksNeeded * (1 + reserveFraction);
        return rocketsLeft < threshold;
    }
}
