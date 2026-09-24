package com.xploits.sweep.core;

import com.xploits.shared.core.i18n.Msg;

/**
 * The rectangle to sweep, in Nether chunk coordinates, with the corners already normalised
 * (min ≤ max on each axis). The player thinks of it in Nether chunks because that is where they fly;
 * {@link #overworldEquivalent()} translates it into Overworld blocks (×8 over Nether blocks, and each
 * chunk is 16 blocks) so they know what real extent they are covering.
 */
public record SweepArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int RATIO_NETHER_OVERWORLD = 8;

    /**
     * Rejects, does not reorder. The component names promise which is the lower and which the upper
     * edge of each axis; if a min &gt; max were silently accepted, {@code chunkCount()} and
     * {@code overworldEquivalent()} could contradict each other in the same call (one announced a
     * negative area, the other a positive count). It follows the precedent of
     * {@code Destination.java}: the canonical constructor validates and rejects, it does not repair;
     * whoever does not know the order of the corners uses {@link #ofChunks} so they are normalised
     * before getting here.
     */
    public SweepArea {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException(
                "minChunkX/minChunkZ must be <= maxChunkX/maxChunkZ respectively; "
                    + "use ofChunks if you do not know the order of the corners in advance");
        }
    }

    /**
     * Builds the area from any two corners, in any order: it normalises each axis separately with
     * min/max, because giving the "upper" corner first is the easiest slip to make when typing
     * coordinates.
     */
    public static SweepArea ofChunks(int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        return new SweepArea(
            Math.min(chunkX1, chunkX2), Math.min(chunkZ1, chunkZ2),
            Math.max(chunkX1, chunkX2), Math.max(chunkZ1, chunkZ2));
    }

    /** Width in chunks, counting both edges: a single-chunk area measures 1, not 0. */
    public int widthInChunks() {
        return maxChunkX - minChunkX + 1;
    }

    /** Height in chunks, counting both edges. */
    public int heightInChunks() {
        return maxChunkZ - minChunkZ + 1;
    }

    /**
     * Total number of chunks in the rectangle. It is multiplied in {@code long} so as not to
     * overflow before checking the range: with width and height as {@code int}, doing the sum
     * directly in {@code int} could wrap around to a valid-looking negative number for large but
     * typeable areas (far below the real world limit). If even so it does not fit in an {@code int},
     * it fails loudly instead of returning that wrapped number.
     */
    public int chunkCount() {
        long total = (long) widthInChunks() * heightInChunks();
        if (total > Integer.MAX_VALUE) {
            throw new ArithmeticException(
                "the area has " + total + " chunks, too many to count in an int");
        }
        return (int) total;
    }

    /**
     * The most chunks an area can have for it to be sweepable, and where the number comes from.
     *
     * <p><b>A cap is needed because two walks of the module cost the whole rectangle</b>, not what
     * the player has seen: {@code Coverage.seenIn} visits every chunk of the area to count the ones
     * already seen, and {@code SweepPlanner} does the same in {@code bandFullySeen} to decide
     * whether a band is skipped. Both run on the main thread from a command, so a typed area of
     * 20,000 x 20,000 chunks -which the settings sliders allow, because {@code sliderRange} only
     * draws the widget and does not bound the value- is 400 million lookups per walk and the client
     * looks frozen. The only brake there was beyond that was the {@code ArithmeticException} of
     * {@link #chunkCount()}, which fires near {@code Integer.MAX_VALUE}, that is <b>after</b> the
     * walks had already tried it.
     *
     * <p><b>Where the 4,000,000 comes from</b>, which is 2,000 x 2,000 chunks:
     *
     * <ul>
     *   <li><b>From above, how long it takes to fly.</b> With a typical measured lane width of about
     *       25 chunks, sweeping an area of N chunks is on the order of {@code N * 16 / 25} blocks of
     *       flight; 4,000,000 chunks are about 2.5 million blocks, that is <b>more than twenty
     *       hours</b> against the ~116,000 blocks and hour-plus of the sweep in spec §6. Nobody plans
     *       that: beyond it, it is a typing error, not a sweep.</li>
     *   <li><b>From below, that it does not get in the way.</b> The whole box the player has crossed
     *       in months on this server is 384 x 580 = 222,720 chunks (spec §1), so the cap leaves
     *       almost twenty times the margin over everything they have ever touched.</li>
     *   <li><b>And what it costs to look at it.</b> Four million per walk, two walks, are about eight
     *       million lookups in a {@code HashSet}: tenths of a second in the worst accepted case,
     *       instead of the tens of seconds of the slider limit.</li>
     * </ul>
     *
     * <p>Besides, below this cap {@link #chunkCount()} cannot overflow, so everything announced about
     * the area can be counted in an {@code int} with no further checks.
     */
    public static final int MAX_CHUNKS = 4_000_000;

    /**
     * The reason why this area is too large to sweep, or {@code null} if it can be swept.
     *
     * <p><b>It is rejected, not trimmed.</b> Trimming the rectangle to a manageable size would leave
     * out terrain the player asked to sweep and would hand them back a "finished" sweep that never
     * looked at that part: the one lie this module cannot tell (spec §9), coming in through a new
     * door. A rejection is seen; a silent trim is not.
     *
     * <p>The reason follows the style of the rest: it gives the size it has, the cap, and <b>how far
     * to shrink the long side</b> keeping the short one, which is the actionable part. It names the
     * four corner settings as they appear in the UI.
     *
     * <p>The count is done in {@code long} and not by calling {@link #chunkCount()} on purpose: this
     * method has to be able to answer precisely about the areas that make that one throw.
     */
    public Msg oversizeRejection() {
        long total = (long) widthInChunks() * heightInChunks();
        if (total <= MAX_CHUNKS) {
            return null;
        }

        int shortSide = Math.min(widthInChunks(), heightInChunks());
        long maxLongSide = MAX_CHUNKS / shortSide;
        // If the long side that would fit is smaller than the short side itself, not even a square of
        // that side fits: telling them "shrink the long side to that number" would send them to a
        // rectangle that still does not fit.
        Msg whatToShrink = maxLongSide < shortSide
            ? Msg.of(SweepText.OVERSIZE_NOTHING_FITS, "short", shortSide)
            : Msg.of(SweepText.OVERSIZE_KEEP_SHORT, "short", shortSide, "max", maxLongSide);

        return Msg.of(SweepText.OVERSIZE, "width", widthInChunks(), "height", heightInChunks(), "total", total,
            "max", MAX_CHUNKS, "fix", whatToShrink);
    }

    /** Size of the rectangle in Overworld blocks, so the player sees what real area it covers. */
    public Msg overworldEquivalent() {
        long widthBlocks = (long) widthInChunks() * BLOCKS_PER_CHUNK * RATIO_NETHER_OVERWORLD;
        long heightBlocks = (long) heightInChunks() * BLOCKS_PER_CHUNK * RATIO_NETHER_OVERWORLD;
        return Msg.of(SweepText.OVERWORLD_EQUIVALENT, "width", widthBlocks, "height", heightBlocks);
    }
}
