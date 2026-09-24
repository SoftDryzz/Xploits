package com.xploits.kitrequester.core;

/**
 * The pure candidate logic behind {@code EnderDepositor} (spec §6.1): which player interaction
 * -or the depositor's own- was seen most recently, and whether it is still trustworthy. Pure: it
 * knows nothing about {@code net.minecraft} or {@code meteordevelopment}. Positions are represented
 * as {@code long} -in the adapter, {@code BlockPos.asLong()}- because at this level the coordinate
 * type does not matter, only whether two interactions land on the same spot.
 *
 * <p>Without this, a screen that opens says nothing on its own about what is behind it: it is the
 * only signal available to tie the operation to a specific block and to know that an unrelated
 * click -block or entity- may have a screen still on its way.
 */
public final class CandidateTracker {
    private final long timeoutMs;

    private boolean hasCandidate;
    private long candidate;
    private long candidateAt;
    /**
     * Whether there has been any entity interaction -minecart or boat with chest, which open a
     * container screen with no {@code BlockPos} to note, because they are not blocks-. A separate
     * field, not a sentinel value in {@link #entityInteractionAt}: before the first interaction
     * there is no timestamp that can be safely subtracted from {@code now} without risking a
     * {@code long} overflow.
     */
    private boolean hasEntityInteraction;
    /** Last time (ms) an entity interaction was seen. Only valid if {@link #hasEntityInteraction}. */
    private long entityInteractionAt;

    public CandidateTracker(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Notes a block interaction as a candidate, if it can really back the screen being expected.
     * {@code opensContainer} is decided by the caller -filtering by block type is Minecraft's job,
     * not this core's-: placing a block, opening a door or the {@code BlockUtils.place} calls of
     * {@code surround}/{@code auto-trap} must not be able to abort a deposit in progress or block
     * the next one (spec §6.1, point 2).
     */
    public void noteBlockInteraction(long pos, boolean opensContainer, long now) {
        if (!opensContainer) return;
        hasCandidate = true;
        candidate = pos;
        candidateAt = now;
    }

    /**
     * Notes an entity interaction. There is no {@code BlockPos} to compare -unlike
     * {@link #noteBlockInteraction}-, so it only feeds {@link #blocksStart}: "something unrelated
     * may be in flight", with no further detail.
     */
    public void noteEntityInteraction(long now) {
        hasEntityInteraction = true;
        entityInteractionAt = now;
    }

    /**
     * Expires the block candidate if it has gone more than {@code timeoutMs} without refreshing.
     * Lazy: only checked when called, never on its own. Call before {@link #blocksStart}.
     */
    public void expire(long now) {
        if (hasCandidate && now - candidateAt > timeoutMs) hasCandidate = false;
    }

    /**
     * true if there is a current block candidate or a recent, unresolved entity interaction
     * -in both cases, an unrelated screen that may not have arrived yet-. For {@code start()}'s
     * guard: refusing to start while this is true is what avoids stepping on an interaction whose
     * screen, once it arrives, would be mistaken for the depositor's own.
     */
    public boolean blocksStart(long now) {
        return hasCandidate || (hasEntityInteraction && now - entityInteractionAt <= timeoutMs);
    }

    /** true if the current block candidate is exactly this position. */
    public boolean matches(long pos) {
        return hasCandidate && candidate == pos;
    }

    /** Forgets the block candidate. The entity interaction is not forgotten: it expires on its own over time. */
    public void clear() {
        hasCandidate = false;
    }
}
