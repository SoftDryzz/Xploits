package com.xploits.pvp.core;

/**
 * The breached-hole memory behind {@link CombatSnapshot#selfHoleBreached()} (task R3-13 fix 2,
 * extracted into its own pure class in review round 1 so it can be tested without a running game).
 * Pure: no {@code net.minecraft}/{@code meteordevelopment} imports (BoundaryTest). Feet positions
 * are represented as {@code long} -in the adapter, {@code BlockPos.asLong()}, the convention
 * {@link com.xploits.kitrequester.core.CandidateTracker} already uses for the same reason- because
 * at this level the coordinate type does not matter, only whether two ticks land on the same block.
 *
 * <p>{@code selfInHole} alone ({@code PlayerUtils.isInHole(false)}) reads false the instant any one
 * of the hole's five protecting blocks -four sides, one below- stops protecting you, which is
 * exactly the tick an enemy has mined in and {@code surround} is needed to patch the gap -and the
 * tick it used to stop being requested (owner's real log, research §0.2). This class is what keeps
 * asking: it remembers the feet block of the last tick you were in a full hole, and reports
 * "breached" for up to {@link CombatDirector#BREACH_MEMORY_TICKS} ticks after leaving it, as long as
 * you stay on that exact block, on the ground, with your height unchanged.
 */
public final class HoleMemory {
    private boolean armed;
    private long feet;
    private long armedAtTick;

    /**
     * One tick of input, entirely in plain values so the adapter is the only thing that ever reads
     * the world: it measures every tick and this class only remembers and compares.
     *
     * <p>Leaving the remembered block, moving vertically, or losing the ground forgets the memory
     * outright, not merely masks it for this tick (spec R3-13 fix 2): a later, unrelated sighting of
     * the same block must not count as a continuation of a breach it never witnessed. Re-arming
     * needs a fresh {@code inHole} tick; standing back on the old block afterwards is not enough.
     *
     * @param tick     a counter that increases by the caller's own convention (one game tick = one
     *                 increment is what the adapter uses); only the difference between two calls
     *                 matters, never its absolute value
     * @param inHole   this tick's {@code PlayerUtils.isInHole(false)}
     * @param onGround whether you are touching the ground this tick
     * @param yChanged whether your height changed on this very tick (the raw one-tick reading,
     *                 {@code lastY != getY()} -not the smoothed, two-tick flag the posture also uses)
     * @param feet     opaque identity of your feet block this tick ({@code BlockPos.asLong()})
     * @return whether {@code selfHoleBreached} should read true this tick
     */
    public boolean tick(long tick, boolean inHole, boolean onGround, boolean yChanged, long feet) {
        if (inHole) {
            armed = true;
            this.feet = feet;
            armedAtTick = tick;
            return false;
        }
        if (!armed) return false;
        if (!onGround || yChanged || feet != this.feet) {
            armed = false;
            return false;
        }
        return tick - armedAtTick <= CombatDirector.BREACH_MEMORY_TICKS;
    }

    /**
     * Forgets the memory outright. The adapter calls this on death, on a world change and on
     * auto-pvp deactivating (task R3-13 fix 2): none of those should let a breach from a previous
     * life, world or activation keep asking for {@code surround} in this one.
     */
    public void forget() {
        armed = false;
    }
}
