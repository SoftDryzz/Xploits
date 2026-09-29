package com.xploits.pvp.recorder.core;

/**
 * What directly hit us in one damage packet, for the bench only: the recorder's tick (the one its damage events
 * carry) and the id of the direct source entity, -1 when there was none. Kept apart from {@link
 * FightRecord.DamageEvent}, which says who caused a hit, so a crystal that the opponent's autobreak set off can
 * still be told from a foreign one by its id, or by its cell when the id was never seen.
 *
 * <p>{@code cell} is the packed block position of the direct crystal when the packet was handled, {@link #NO_CELL}
 * when it was not a crystal in the world. Kept in memory only; never logged or written.
 */
public record HitSource(long tick, int directId, long cell) {
    public static final long NO_CELL = Long.MIN_VALUE;

    public HitSource(long tick, int directId) {
        this(tick, directId, NO_CELL);
    }
}
