package com.xploits.travel.core;

/**
 * The firework warning decision (AutoTravel spec §8: <i>"Out of fireworks mid-flight → Loud warning:
 * toast and sound. Finding out at 100k matters"</i>). Everything that can be decided without touching
 * Minecraft lives here: from how many fireworks the warning fires and when it repeats. Counting the
 * inventory's fireworks and showing the toast belong to the adapter.
 *
 * <p><b>Why a class is needed for an {@code if}.</b> The flight is observed once per tick, so a
 * warning without memory would go out twenty times per second until the end of the trip and would
 * cover anything else on screen. And the naive memory -a {@code boolean} set to {@code true} and never
 * cleared- has the opposite flaw: if the player restocks fireworks from a shulker and later runs out
 * again, the module would stay mute for the rest of the trip, which is precisely when the warning
 * matters most.
 *
 * <p>The way out is a hysteresis with a single boundary: it warns on entering the danger band
 * ({@code fireworks <= threshold}) and the warning <b>rearms</b> on leaving it ({@code fireworks >
 * threshold}). During the flight the count only goes down -each boost spends one-, so leaving the
 * band means exactly one thing: that they have been restocked.
 */
public final class FireworkWatch {
    private final int threshold;

    /** Whether it already warned and has not yet left the danger band. */
    private boolean warned;

    /**
     * @param threshold with this many fireworks or fewer it warns. Zero means warning only when they
     *                  run out completely; any larger value warns with some margin, which is the point
     *                  a hundred thousand blocks from home
     */
    public FireworkWatch(int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("the firework threshold cannot be negative: " + threshold);
        }
        this.threshold = threshold;
    }

    /**
     * Observes this tick's firework count and says whether the loud warning has to go out now.
     *
     * @return {@code true} only once for each entry into the danger band
     */
    public boolean observe(int fireworks) {
        if (fireworks > threshold) {
            // Outside the band: they have been restocked, or it was never entered. The warning is armed again.
            warned = false;
            return false;
        }
        if (warned) return false;
        warned = true;
        return true;
    }

    /** Goes back to the freshly started state, with the warning armed. For the start of a trip. */
    public void reset() {
        warned = false;
    }

    public int threshold() {
        return threshold;
    }
}
