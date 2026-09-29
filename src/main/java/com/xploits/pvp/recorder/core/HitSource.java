package com.xploits.pvp.recorder.core;

/**
 * What directly hit us in one damage packet, for the bench only: the recorder's tick (the one its damage events
 * carry) and the id of the direct source entity, -1 when there was none. Kept apart from {@link
 * FightRecord.DamageEvent}, which says who caused a hit, so a crystal that the opponent's autobreak set off can
 * still be told from a foreign one by its id.
 */
public record HitSource(long tick, int directId) {
}
