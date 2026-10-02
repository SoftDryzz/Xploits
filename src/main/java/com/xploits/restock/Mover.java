package com.xploits.restock;

import com.xploits.printer.core.BaritoneSession;
import com.xploits.restock.core.RestockReason;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * How restock walks (restock spec §3 "The trip"; the printer's seam I9): Baritone through chat commands in the game, a
 * scripted walker in the bench. No container click happens while {@link #idle()} is false. Client thread only.
 */
public interface Mover {
    /** Whether walking is possible at all. */
    boolean available();

    /** Prepares one restock session (Baritone: read, save and set its values); a refusal when it cannot. */
    Optional<RestockReason.Refusal> begin(String prefix, BaritoneSession.Mode mode);

    /** Walks to stand exactly on {@code feet}; false when the command did not reach the walker. */
    boolean goTo(BlockPos feet);

    /** Heads towards a column not loaded yet; false when the command did not reach the walker. */
    boolean goToward(int x, int z);

    /** Stops the current goal (Baritone: {@code #cancel}). */
    void cancel();

    /** No goal may be active. */
    boolean idle();

    /** Called at the start of every restock tick (the bench's walker moves here; Baritone walks by itself). */
    void tick();

    /** Ends the session: cancel first, then give back what {@link #begin} changed; false when it did not arrive. */
    boolean end();

    /** The prefix the session speaks with, for messages; empty before {@link #begin}. */
    String prefix();
}
